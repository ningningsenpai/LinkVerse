"""全量配对复评与分群报告，历史回归结果不冒充确认性盲测。"""

from __future__ import annotations

import csv
import json
import statistics
import time
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
import pandas as pd

from linkverse_recommendation.core.metrics import ndcg_at_k, recall_at_k
from linkverse_recommendation.data.split import fixed_temporal_split, temporal_split
from linkverse_recommendation.serving.registry import LoadedTradeModel
from linkverse_recommendation.training.recording import record, write_json


def _evaluation_time(split, request_time):
    if request_time is None:
        return None
    request_time = pd.Timestamp(request_time)
    if request_time.tzinfo is None:
        raise ValueError("评估请求时间必须携带时区")
    history = (*split.train, *split.validation, *split.feedback)
    if any(row.get("label_available_at", row["event_time"]) >= request_time for row in history):
        raise ValueError("评估排除历史包含请求时尚不可用的标签")
    if request_time > min(row["event_time"] for row in split.test):
        raise ValueError("评估请求时间不得晚于测试窗口首个目标")
    return request_time


def evaluate_models(dataset: Path, bundles: dict[str, Path], output: Path, split_path: Path | None = None,
                    additional_models: dict | None = None, request_time=None) -> dict:
    """相同最终窗口评估冻结模型，保存匿名逐用户证据和配对区间。"""
    from linkverse_recommendation.training.trainer import _normalize_interactions, _normalize_items

    items = _normalize_items(pd.read_parquet(dataset / "items.parquet"))
    manifest = json.loads((dataset / "manifest.json").read_text(encoding="utf-8"))
    rows = _normalize_interactions(pd.read_parquet(dataset / "interactions.parquet"), as_of=manifest.get("exported_at")).to_dict("records")
    split = fixed_temporal_split(rows, split_path) if split_path else temporal_split(rows, key=lambda row: row["event_time"])
    request_time = _evaluation_time(split, request_time)
    models = {name: LoadedTradeModel(path) for name, path in bundles.items()}
    models.update(additional_models or {})
    if request_time is not None and any(pd.Timestamp(model.serving_state["fit_cutoff"]) >= request_time for model in models.values()):
        raise ValueError("评估请求时间必须晚于所有召回模型训练截止时间")
    catalog = set.intersection(*(set(model.object_ids) for model in models.values()))
    categories = dict(zip(items["listing_id"], items["category_code"], strict=True))
    histories = defaultdict(set)
    history_counts = Counter()
    for row in (*split.train, *split.validation, *split.feedback):
        if row["gain"] > 0:
            histories[str(row["user_key"])].add(str(row["object_id"]))
            history_counts[str(row["user_key"])] += 1
    targets = defaultdict(dict)
    for row in split.test:
        user = str(row["user_key"])
        targets[user]
        if row["gain"] > 0:
            item = str(row["object_id"])
            targets[user][item] = max(targets[user].get(item, 0), float(row["gain"]))
    output.mkdir(parents=True, exist_ok=True)
    (output / "raw").mkdir(exist_ok=True)
    result = {"evaluation_version": 4, "scope": "HISTORICAL_SYNTHETIC_REGRESSION" if rows[0]["data_source"] == "SYNTHETIC" else "LOCAL_SCRIPTED_REGRESSION", "quality_claim_allowed": False, "users": len(targets), "test_rows": len(split.test), "catalog": len(catalog), "models": {}}
    if request_time is not None:
        result.update(evaluation_version=5, request_time=request_time.isoformat())
    per_model = {}
    csv_rows = []
    for name, model in models.items():
        observations = []
        covered = set()
        for user, gains in sorted(targets.items()):
            started = time.perf_counter()
            # 公共排除集合在新服务的最终重排之前生效，避免评估器移除历史后再次破坏配额。
            excluded = histories[user] | (set(model.object_ids) - catalog)
            options = {"occurred_at": request_time.to_pydatetime()} if request_time is not None else {}
            ids = [item.object_id for item in model.recommend(user, 300, context={"exclude_ids": ",".join(sorted(excluded))}, **options)]
            elapsed = (time.perf_counter() - started) * 1000
            eligible_gains = {item: gain for item, gain in gains.items() if item in catalog and item not in histories[user]}
            eligible = [item for item in ids if item in catalog and item not in histories[user]][:50]
            category_counts = Counter(categories[item] for item in eligible[:20])
            covered.update(eligible[:20])
            observation = {
                "user_key": user, "model": name, "history_count": history_counts[user],
                "cohort": "cold" if user not in model.user_vocabulary else ("sparse" if history_counts[user] < 5 else "warm"),
                "target_count": len(gains), "eligible_target_count": len(eligible_gains),
                "raw_ndcg_at_20": ndcg_at_k(ids, gains, 20) if gains else None,
                "raw_recall_at_50": recall_at_k(ids, set(gains), 50) if gains else None,
                "ndcg_at_20": ndcg_at_k(eligible, eligible_gains, 20) if eligible_gains else None,
                "recall_at_50": recall_at_k(eligible, set(eligible_gains), 50) if eligible_gains else None,
                "hit_rate_at_20": float(bool(set(eligible[:20]) & eligible_gains.keys())) if eligible_gains else None,
                "latency_ms": elapsed, "returned": len(eligible[:20]), "category_quota_exceeded": max(category_counts.values(), default=0) > 6,
            }
            observations.append(observation)
        per_model[name] = observations
        with (output / "raw" / f"users-{name}.jsonl").open("w", encoding="utf-8") as stream:
            for observation in observations:
                stream.write(json.dumps(observation, ensure_ascii=False) + "\n")
        metrics = _aggregate(observations)
        metrics.update(model_version=model.model_version, coverage_at_20=len(covered) / max(1, len(catalog)))
        result["models"][name] = metrics
        csv_rows.append({"model": name, "cohort": "all", **metrics})
        for cohort in ("cold", "sparse", "warm"):
            csv_rows.append({"model": name, "cohort": cohort, **_aggregate([row for row in observations if row["cohort"] == cohort])})
        record("stages", stage="evaluation", model=name, status="COMPLETED", metrics=metrics)
    names = list(models)
    if len(names) >= 2:
        left, right = per_model[names[0]], per_model[names[-1]]
        deltas = [b["ndcg_at_20"] - a["ndcg_at_20"] for a, b in zip(left, right, strict=True) if a["ndcg_at_20"] is not None and b["ndcg_at_20"] is not None]
        generator = np.random.default_rng(20260906)
        estimates = [float(generator.choice(deltas, size=len(deltas), replace=True).mean()) for _ in range(1000)] if deltas else []
        result["paired_comparison"] = {"before": names[0], "after": names[-1], "users": len(deltas), "ndcg_delta": statistics.fmean(deltas) if deltas else None, "bootstrap_95": np.quantile(estimates, [.025, .975]).tolist() if estimates else None, "confirmatory": False}
    write_json(output / "evaluation.json", result)
    fields = list(dict.fromkeys(key for row in csv_rows for key in row))
    with (output / "cohort-metrics.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(csv_rows)
    text = ["# 全量回归评估", "", "本报告使用已查看的历史/本地回归窗口，不构成自然用户质量声明。", "", "| 模型 | 可评估用户 | NDCG@20 | Recall@50 | 覆盖率 |", "|---|---:|---:|---:|---:|"]
    for name, metrics in result["models"].items():
        text.append(f"| {name} | {metrics['eligible_users']} | {metrics['ndcg_at_20']} | {metrics['recall_at_50']} | {metrics['coverage_at_20']} |")
    text.extend(["", "原始候选与公共历史过滤后的指标分开保存；无正标签记录为 null，分母单独列出。", "", f"配对结果：`{json.dumps(result.get('paired_comparison'), ensure_ascii=False)}`"])
    (output / "comparison.md").write_text("\n".join(text) + "\n", encoding="utf-8")
    return result


def _aggregate(rows: list[dict]) -> dict:
    result = {"users": len(rows), "eligible_users": sum(row["ndcg_at_20"] is not None for row in rows), "no_positive_users": sum(row["target_count"] == 0 for row in rows)}
    for name in ("raw_ndcg_at_20", "raw_recall_at_50", "ndcg_at_20", "recall_at_50", "hit_rate_at_20"):
        values = [row[name] for row in rows if row[name] is not None]
        result[name] = statistics.fmean(values) if values else None
    result["category_violations"] = sum(row["category_quota_exceeded"] for row in rows)
    result["short_lists"] = sum(row["returned"] < 20 for row in rows)
    result["latency_p95_ms"] = float(np.quantile([row["latency_ms"] for row in rows], .95)) if rows else None
    return result
