"""冻结早期召回包，用后续时间窗口训练与验证线上候选精排。"""

from __future__ import annotations

import argparse
import json
import shutil
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

from linkverse_recommendation.core.metrics import grouped_tie_aware_ndcg, ndcg_at_k, recall_at_k
from linkverse_recommendation.core.model_bundle import sha256_file, write_manifest
from linkverse_recommendation.data.split import fixed_temporal_split
from linkverse_recommendation.pipeline.ranking.features import HISTORY_FEATURES, RECALL_FEATURES
from linkverse_recommendation.pipeline.ranking.lambda_rank import LambdaRankConfig, LambdaRanker
from linkverse_recommendation.serving.registry import LoadedTradeModel
from linkverse_recommendation.training.evaluation import evaluate_models
from linkverse_recommendation.training.recording import RunRecorder, metric_scope, record, run_identity, write_json
from linkverse_recommendation.training.trainer import _dataset_hash, _normalize_interactions
from linkverse_recommendation.training.tuning import tune_lambda_rank


def window_queries(model, rows: list[dict], start, end) -> list[dict]:
    """窗口开始时请求一次，后续成熟标签作目标；历史只用于请求前排除。"""
    cutoff = pd.Timestamp(model.serving_state["fit_cutoff"])
    if cutoff >= start:
        raise ValueError("召回模型训练截止时间必须早于精排请求窗口")
    history, targets = defaultdict(set), defaultdict(dict)
    catalog = model.serving_state["items"]
    for row in rows:
        available = row.get("label_available_at", row["event_time"])
        user, item = str(row["user_key"]), str(row["object_id"])
        if available < start and row["gain"] > 0:
            history[user].add(item)
        if start <= row["event_time"] < end and available < end and row["gain"] > 0:
            targets[user][item] = max(targets[user].get(item, 0), int(row["gain"]))
    queries = []
    for user, gains in sorted(targets.items()):
        eligible = {item: gain for item, gain in gains.items()
                    if item in catalog and item not in history[user]
                    and pd.Timestamp(catalog[item]["published_at"]) <= start}
        if eligible:
            queries.append({"user_key": user, "gains": eligible, "now": start.to_pydatetime(),
                            "context": {"exclude_ids": ",".join(sorted(history[user]))}})
    return queries


def group_dataset(model, queries: list[dict], output: Path, seed: int):
    """只训练召回命中的组；不命中用户仍计入全链路评估分母。"""
    generator = np.random.default_rng(seed)
    matrices, labels, groups, hits = [], [], [], 0
    with output.with_suffix(".jsonl").open("x", encoding="utf-8") as stream:
        for query in queries:
            ids, _, features, _ = model.candidate_features(
                query["user_key"], context=query["context"], occurred_at=query["now"], feature_version=3,
            )
            y = np.asarray([query["gains"].get(item, 0) for item in ids], dtype=np.int32)
            usable = bool(np.any(y > 0) and np.any(y == 0))
            if usable:
                order = generator.permutation(len(ids))
                matrices.append(features[order])
                labels.append(y[order])
                groups.append(len(ids))
                hits += 1
            stream.write(json.dumps({"user_key": query["user_key"], "query_time": query["now"].isoformat(),
                                     "target_count": len(query["gains"]), "retrieved_targets": int(np.sum(y > 0)),
                                     "candidate_count": len(ids), "fit_group": usable,
                                     "candidate_ids": [ids[i] for i in order] if usable else ids,
                                     "target_gains": query["gains"]}, ensure_ascii=False) + "\n")
    if not groups:
        raise ValueError("候选池没有可比较的正负样本组")
    result = np.concatenate(matrices), np.concatenate(labels), groups
    np.savez_compressed(output, features=result[0], labels=result[1], groups=groups)
    audit = {"queries": len(queries), "fit_groups": hits, "missed_or_constant_groups": len(queries) - hits,
             "rows": len(result[1]), "positive_rows": int(np.sum(result[1] > 0)),
             "group_min": min(groups), "group_max": max(groups), "feature_count": result[0].shape[1],
             "feature_archive_sha256": sha256_file(output), "positive_injection": False}
    record("candidate-audit", phase=output.stem, **audit)
    return result, audit


def validation_scores(model, queries, variants, output):
    rows = {name: [] for name in variants}
    covered = {name: set() for name in variants}
    for position, query in enumerate(queries):
        ids, raw, features, now = model.candidate_features(
            query["user_key"], context=query["context"], occurred_at=query["now"], feature_version=3,
        )
        for name, variant in variants.items():
            booster, width, policy, *scale = variant
            predictions = booster.predict(features[:, :width], num_threads=1)
            recommendations = model.rank_candidates(ids, raw, predictions, now, 300, policy, scale[0] if scale else 1)
            selected = [item.object_id for item in recommendations]
            categories = Counter(model.serving_state["items"][item]["category_code"] for item in selected[:20])
            sellers = Counter(model.serving_state["items"][item]["seller_key"] for item in selected[:20])
            covered[name].update(selected[:20])
            rows[name].append({"user_key": query["user_key"], "ndcg_at_20": ndcg_at_k(selected, query["gains"], 20),
                               "recall_at_50": recall_at_k(selected, set(query["gains"]), 50),
                               "short_list": len(selected) < 20,
                               "quota_violation": max(categories.values(), default=0) > 6 or max(sellers.values(), default=0) > 3})
        if (position + 1) % 500 == 0:
            record("stages", stage="validation", users=position + 1, total=len(queries))
    result = {}
    for name, observations in rows.items():
        with (output / "raw" / f"validation-{name}.jsonl").open("x", encoding="utf-8") as stream:
            stream.writelines(json.dumps(row) + "\n" for row in observations)
        result[name] = {"users": len(observations),
                        **{key: float(np.mean([row[key] for row in observations])) for key in ("ndcg_at_20", "recall_at_50")},
                        "coverage_at_20": len(covered[name]) / len(model.object_ids),
                        "short_lists": sum(row["short_list"] for row in observations),
                        "quota_violations": sum(row["quota_violation"] for row in observations)}
    return result


def write_ranker_bundle(base, target, booster, width, policy, provenance, metrics, score_scale: float = 1):
    """复制冻结召回文件，仅替换精排与声明；旧包及活动指针保持可回滚。"""
    shutil.copytree(base, target)
    booster.save_model(str(target / "lambda-rank.txt"))
    write_json(target / "feature-schema.json", {"version": 3 if width == len(RECALL_FEATURES) else 2,
               "ranker_features": RECALL_FEATURES if width == len(RECALL_FEATURES) else HISTORY_FEATURES,
               "time_semantics": "frozen_recall_snapshot_with_request_time_features", "mmr_score_policy": policy,
               "mmr_score_scale": score_scale})
    write_json(target / "metrics.json", metrics)
    original = json.loads((base / "manifest.json").read_text(encoding="utf-8"))
    write_manifest(target, target.name, original["data_hash"], original["dependencies_lock_hash"], provenance)
    return LoadedTradeModel(target)


def execute(config_path: Path):
    config = json.loads(config_path.read_text(encoding="utf-8"))
    output, base = Path(config["output"]), Path(config["base_bundle"])
    with RunRecorder(output, config, Path(__file__).resolve().parents[5]):
        model = LoadedTradeModel(base)
        dataset = Path(config["dataset"])
        if _dataset_hash(dataset) != model.manifest["data_hash"]:
            raise ValueError("精排数据快照与冻结召回包的数据摘要不一致")
        manifest = json.loads((dataset / "manifest.json").read_text(encoding="utf-8"))
        rows = _normalize_interactions(pd.read_parquet(dataset / "interactions.parquet"), as_of=manifest.get("exported_at")).to_dict("records")
        # 未曝光对象不能作为真实负例；本轮仅开放合成回归路径，真实数据需要请求归因组。
        if {row["data_source"] for row in rows} != {"SYNTHETIC"}:
            raise ValueError("本轮候选负样本仅允许合成回归数据")
        split_path = output / "split-manifest.json"
        shutil.copyfile(config["split_manifest"], split_path)
        split = fixed_temporal_split(rows, split_path)
        boundaries = json.loads(split_path.read_text(encoding="utf-8"))["boundaries"]
        start, middle, end = pd.Timestamp(boundaries[1]), pd.Timestamp(config["rank_validation_start"]), pd.Timestamp(boundaries[2])
        if not start < middle < end:
            raise ValueError("精排时间边界必须位于冻结反馈窗口内部")
        train_queries = window_queries(model, rows, start, middle)
        valid_queries = window_queries(model, rows, middle, end)
        if config.get("smoke_users"):
            train_queries = train_queries[:config["smoke_users"]]
            valid_queries = valid_queries[:config["smoke_users"]]
        write_json(output / "time-audit.json", {
            "recall_fit_cutoff": model.serving_state["fit_cutoff"], "rank_train_start": start,
            "rank_train_end_exclusive": middle, "rank_validation_end_exclusive": end,
            "base_manifest_sha256": sha256_file(base / "manifest.json"),
            "split_sha256": sha256_file(split_path), "dataset_manifest_sha256": sha256_file(dataset / "manifest.json"),
            "rank_train_queries": len(train_queries), "rank_validation_queries": len(valid_queries),
            "test_rows_reserved": len(split.test), "test_previously_viewed": True,
        })
        seeds = config["seeds"]
        train, train_audit = group_dataset(model, train_queries, output / "raw" / "train-groups.npz", seeds[0])
        valid, valid_audit = group_dataset(model, valid_queries, output / "raw" / "valid-groups.npz", seeds[0] + 1)
        variants = {"legacy": (model.ranker, 5, "raw")}
        training = {}
        for name, width in (("history", 5), ("recall", len(RECALL_FEATURES))):
            x, y, groups = train
            vx, vy, vgroups = valid
            def objective(parameters):
                fitted = LambdaRanker(LambdaRankConfig(**parameters)).fit(
                    x[:, :width], y, groups, validation=(vx[:, :width], vy, vgroups),
                    rounds=config["max_rounds"], seed=seeds[0],
                )
                return grouped_tie_aware_ndcg(fitted.predict(vx[:, :width]), vy, vgroups, 20)
            with metric_scope(variant=name):
                study = tune_lambda_rank(objective, config["ranker_trials"], seeds[0])
                with metric_scope(stage="selected_refit"):
                    fitted = LambdaRanker(LambdaRankConfig(**study.best_params)).fit(
                        x[:, :width], y, groups, validation=(vx[:, :width], vy, vgroups),
                        rounds=config["max_rounds"], seed=seeds[0],
                    )
            variants[f"{name}_raw"] = (fitted.booster, width, "raw")
            variants[f"{name}_percentile"] = (fitted.booster, width, "percentile")
            fitted.save(output / f"{name}-lambda-rank.txt")
            training[name] = {"parameters": study.best_params, "best_validation_group_ndcg": study.best_value,
                              "best_iteration": fitted.booster.best_iteration, "trees": fitted.booster.num_trees(),
                              "feature_importance_gain": dict(zip(RECALL_FEATURES[:width], fitted.booster.feature_importance("gain").tolist()))}
            write_json(output / "training.json", training)
        validation = validation_scores(model, valid_queries, variants, output)
        # 配额、列表和覆盖率作为选择约束；只用验证窗口选择，随后立即冻结模型摘要。
        baseline = validation["legacy"]
        eligible = [name for name, value in validation.items()
                    if not value["short_lists"] and not value["quota_violations"]
                    and value["coverage_at_20"] >= baseline["coverage_at_20"] * .9]
        if not eligible:
            raise ValueError("验证窗口没有满足完整列表与覆盖率的候选")
        selected = max(eligible, key=lambda name: validation[name]["ndcg_at_20"])
        write_json(output / "validation.json", validation)
        booster, width, policy = variants[selected]
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S%f")
        target = Path(config["model_root"]) / f"trade-{stamp}-{run_identity()['git_commit'][:8]}-{model.manifest['data_hash'][:8]}"
        target.parent.mkdir(parents=True, exist_ok=True)
        provenance = {**run_identity(), "base_manifest_sha256": sha256_file(base / "manifest.json"),
                      "ranker_metric_version": 2, "ranker_group_version": 3,
                      "rank_fit_start": str(start), "rank_fit_end_exclusive": str(middle),
                      "rank_validation_end_exclusive": str(end), "selected_variant": selected,
                      "feature_version": 3 if width == len(RECALL_FEATURES) else 2,
                      "quality_claim_allowed": False, "purpose": "ENGINEERING"}
        bundle_model = write_ranker_bundle(base, target, booster, width, policy, provenance,
                                           {"validation": validation, "quality_claim_allowed": False})
        selection = {"selected": selected, "bundle": str(target), "manifest_sha256": sha256_file(target / "manifest.json"),
                     "frozen_before_test": True, "validation": validation[selected], "train_audit": train_audit,
                     "valid_audit": valid_audit, "quality_claim_allowed": False}
        write_json(output / "selection.json", selection)
        reproducibility = []
        if selected != "legacy":
            name = selected.split("_")[0]
            for seed in [*seeds, seeds[0]]:
                with metric_scope(variant=name, stage="seed_repeat", seed=seed):
                    fitted = LambdaRanker(LambdaRankConfig(**training[name]["parameters"])).fit(
                        train[0][:, :width], train[1], train[2], rounds=training[name]["best_iteration"], seed=seed,
                    )
                predictions = fitted.predict(valid[0][:, :width])
                np.save(output / "raw" / f"seed-{seed}-{len(reproducibility)}.npy", predictions, allow_pickle=False)
                reproducibility.append({"seed": seed, "group_ndcg": grouped_tie_aware_ndcg(predictions, valid[1], valid[2], 20),
                                        "maximum_difference_from_selected": float(np.max(np.abs(predictions - booster.predict(valid[0][:, :width], num_threads=1))))})
            write_json(output / "reproducibility.json", reproducibility)
        if not config.get("skip_test", False):
            result = evaluate_models(dataset, {"before": base, "after": target}, output / "test", split_path,
                                     request_time=end.to_pydatetime())
            write_json(output / "result.json", result)
        # 模型包在评估后不得写回指标，否则证据摘要将失效。
        if sha256_file(target / "manifest.json") != selection["manifest_sha256"]:
            raise ValueError("评估期间冻结模型包摘要发生变化")
        record("stages", stage="ranker_iteration", status="COMPLETED", selected=selected,
               model_version=bundle_model.model_version)
    return output


def main():
    parser = argparse.ArgumentParser(description="执行冻结召回的精排升级")
    parser.add_argument("--config", required=True, type=Path)
    print(execute(parser.parse_args().config))


if __name__ == "__main__":
    main()
