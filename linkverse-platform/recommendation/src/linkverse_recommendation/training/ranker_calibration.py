"""在既定验证窗口做有界分数尺度校准，不读取测试指标选参。"""

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd

from linkverse_recommendation.core.metrics import grouped_tie_aware_ndcg
from linkverse_recommendation.core.model_bundle import sha256_file
from linkverse_recommendation.pipeline.ranking.features import RECALL_FEATURES
from linkverse_recommendation.pipeline.ranking.lambda_rank import LambdaRankConfig, LambdaRanker
from linkverse_recommendation.serving.registry import LoadedTradeModel
from linkverse_recommendation.training.ranker_iteration import validation_scores, window_queries, write_ranker_bundle
from linkverse_recommendation.training.recording import RunRecorder, metric_scope, record, run_identity, write_json
from linkverse_recommendation.training.trainer import _dataset_hash, _normalize_interactions


def execute(config_path: Path):
    config = json.loads(config_path.read_text(encoding="utf-8"))
    output, parent = Path(config["output"]), Path(config["parent_run"])
    with RunRecorder(output, config, Path(__file__).resolve().parents[5]):
        prior = json.loads((parent / "config.json").read_text(encoding="utf-8"))
        audit = json.loads((parent / "selection.json").read_text(encoding="utf-8"))
        time_audit = json.loads((parent / "time-audit.json").read_text(encoding="utf-8"))
        base = Path(prior["base_bundle"])
        if sha256_file(base / "manifest.json") != time_audit["base_manifest_sha256"]:
            raise ValueError("冻结召回包摘要发生变化")
        for name in ("train", "valid"):
            if sha256_file(parent / "raw" / f"{name}-groups.npz") != audit[f"{name}_audit"]["feature_archive_sha256"]:
                raise ValueError("冻结训练或验证特征发生变化")
        write_json(output / "inputs.json", {name: sha256_file(parent / name) for name in
                   ("config.json", "training.json", "validation.json", "selection.json", "recall-lambda-rank.txt")})
        model = LoadedTradeModel(base)
        booster = lgb.Booster(model_file=str(parent / "recall-lambda-rank.txt"))
        scales = config["score_scales"]
        if any(not 0 < scale < 1 for scale in scales) or len(set(scales)) != len(scales):
            raise ValueError("校准比例必须互不重复且严格位于零和一之间")
        dataset = Path(prior["dataset"])
        if _dataset_hash(dataset) != model.manifest["data_hash"]:
            raise ValueError("校准数据快照与冻结召回包的数据摘要不一致")
        manifest = json.loads((dataset / "manifest.json").read_text(encoding="utf-8"))
        rows = _normalize_interactions(pd.read_parquet(dataset / "interactions.parquet"), as_of=manifest.get("exported_at")).to_dict("records")
        middle = pd.Timestamp(prior["rank_validation_start"])
        end = pd.Timestamp(time_audit["rank_validation_end_exclusive"])
        queries = window_queries(model, rows, middle, end)
        variants = {f"recall_scale_{scale:g}": (booster, len(RECALL_FEATURES), "raw", scale) for scale in scales}
        validation = validation_scores(model, queries, variants, output)
        baseline = json.loads((parent / "validation.json").read_text(encoding="utf-8"))["legacy"]
        validation["legacy"] = baseline
        eligible = [name for name, value in validation.items() if not value["short_lists"] and not value["quota_violations"]
                    and value["coverage_at_20"] >= baseline["coverage_at_20"] * .9]
        selected = max(eligible, key=lambda name: validation[name]["ndcg_at_20"])
        if selected == "legacy":
            chosen, width, policy, scale = model.ranker, 5, "raw", 1
        else:
            chosen, width, policy, scale = variants[selected]
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S%f")
        target = Path(prior["model_root"]) / f"trade-{stamp}-{run_identity()['git_commit'][:8]}-{model.manifest['data_hash'][:8]}"
        provenance = {**run_identity(), "parent_training_run": str(parent), "selected_variant": selected,
                      "base_manifest_sha256": time_audit["base_manifest_sha256"], "ranker_metric_version": 2,
                      "ranker_group_version": 3, "feature_version": 3 if width == len(RECALL_FEATURES) else 2,
                      "fit_and_validation_boundaries": time_audit, "quality_claim_allowed": False, "purpose": "ENGINEERING"}
        write_ranker_bundle(base, target, chosen, width, policy, provenance,
                            {"validation": validation, "quality_claim_allowed": False}, scale)
        selection = {"selected": selected, "bundle": str(target), "manifest_sha256": sha256_file(target / "manifest.json"),
                     "validation": validation, "frozen_before_candidate_test": True, "quality_claim_allowed": False}
        write_json(output / "selection.json", selection)
        write_json(output / "validation.json", validation)
        record("stages", stage="selection", status="FROZEN", selected=selected)
        if selected != "legacy":
            train = np.load(parent / "raw" / "train-groups.npz", allow_pickle=False)
            valid = np.load(parent / "raw" / "valid-groups.npz", allow_pickle=False)
            trained = json.loads((parent / "training.json").read_text(encoding="utf-8"))["recall"]
            selected_predictions = chosen.predict(valid["features"], num_threads=1)
            repetitions = []
            for index, seed in enumerate([*prior["seeds"], prior["seeds"][0]]):
                with metric_scope(stage="seed_repeat", seed=seed):
                    fitted = LambdaRanker(LambdaRankConfig(**trained["parameters"])).fit(
                        train["features"], train["labels"], train["groups"], rounds=trained["best_iteration"], seed=seed)
                predictions = fitted.predict(valid["features"])
                np.save(output / "raw" / f"seed-{seed}-{index}.npy", predictions, allow_pickle=False)
                fitted.save(output / "raw" / f"seed-{seed}-{index}.txt")
                repetitions.append({"seed": seed, "trees": fitted.booster.num_trees(),
                                    "validation_group_ndcg": grouped_tie_aware_ndcg(predictions, valid["labels"], valid["groups"]),
                                    "maximum_difference_from_selected": float(np.max(np.abs(predictions - selected_predictions)))})
                write_json(output / "reproducibility.json", repetitions)
        print(json.dumps(selection, ensure_ascii=False))
    return output


def main():
    parser = argparse.ArgumentParser(description="执行验证窗口的有界精排尺度校准")
    parser.add_argument("--config", type=Path, required=True)
    execute(parser.parse_args().config)


if __name__ == "__main__":
    main()
