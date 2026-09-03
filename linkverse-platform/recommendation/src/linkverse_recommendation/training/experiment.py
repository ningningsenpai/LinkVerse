"""训练前后对比、固定画像回放与实验报告统一生成命令。"""

from __future__ import annotations

import argparse
import csv
import json
import math
import shutil
import statistics
import time
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd

from linkverse_recommendation.core.metrics import catalog_coverage, hit_rate_at_k, ndcg_at_k, recall_at_k
from linkverse_recommendation.data.split import temporal_split
from linkverse_recommendation.serving.registry import LoadedTradeModel
from linkverse_recommendation.training.trainer import (
    _normalize_interactions,
    _normalize_items,
    train_trade_model,
)


plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False


def run_experiment(
    before_dataset: Path,
    after_dataset: Path,
    model_root: Path,
    experiment_root: Path,
    two_tower_trials: int,
    ranker_trials: int,
) -> Path:
    """执行初始训练、反馈后完整重训和同一最终窗口盲测，并生成全部可提交报告。"""

    run_id = datetime.now(timezone.utc).strftime("trade-%Y%m%dT%H%M%SZ")
    run = experiment_root / "runs" / run_id
    (run / "personas").mkdir(parents=True, exist_ok=False)
    (run / "plots").mkdir()

    before = train_trade_model(before_dataset, model_root / "before", two_tower_trials, ranker_trials, False)
    after = train_trade_model(
        after_dataset,
        model_root / "after",
        two_tower_trials,
        ranker_trials,
        True,
        tower_parameters=before["two_tower_best_params"],
        ranker_parameters=before["lambda_rank_best_params"],
    )
    before_model = LoadedTradeModel(Path(before["bundle"]))
    after_model = LoadedTradeModel(Path(after["bundle"]))

    items, interactions = _write_evaluation_reports(
        run, before_dataset, before_model, after_model, before, after
    )
    _write_tuning(run / "tuning-summary.csv", before, after)
    _write_manifest(run, run_id, before, after, before_dataset, after_dataset)
    _write_audit(run, interactions, items, before_dataset)
    _write_model_card(run, before, after)
    return run


def refresh_evaluation_reports(
    run: Path,
    dataset: Path,
    before_bundle: Path,
    after_bundle: Path,
) -> None:
    """复用已校验模型包重新计算用户回放、离线指标和图表，不触碰训练产物。"""

    before_model = LoadedTradeModel(before_bundle)
    after_model = LoadedTradeModel(after_bundle)
    items, interactions = _write_evaluation_reports(
        run,
        dataset,
        before_model,
        after_model,
        {"model_version": before_model.model_version},
        {"model_version": after_model.model_version},
    )
    split = temporal_split(
        interactions.to_dict("records"), key=lambda row: pd.to_datetime(row["event_time"], utc=True)
    )
    split_rows = {
        "train": len(split.train),
        "validation": len(split.validation),
        "feedback": len(split.feedback),
        "test": len(split.test),
    }
    before = _load_report_result(run, before_bundle, "before", split_rows)
    after = _load_report_result(run, after_bundle, "after", split_rows)
    _write_model_card(run, before, after)
    _write_audit(run, interactions, items, dataset)
    _write_manifest(run, run.name, before, after, dataset, dataset)


def _load_report_result(run, bundle, stage, split_rows):
    metrics = json.loads((bundle / "metrics.json").read_text(encoding="utf-8"))
    tuning = pd.read_csv(run / "tuning-summary.csv")
    tower = tuning[(tuning["stage"] == stage) & (tuning["model"] == "two_tower")].iloc[0]
    ranker = tuning[(tuning["stage"] == stage) & (tuning["model"] == "lambda_rank")].iloc[0]
    return {
        "model_version": bundle.name,
        "cuda": metrics["cuda"],
        "split_rows": split_rows,
        "two_tower_seed_ndcg_at_20": metrics["ndcg_at_20_by_seed"],
        "two_tower_ndcg_at_20_mean": metrics["ndcg_at_20_mean"],
        "two_tower_ndcg_at_20_std": metrics["ndcg_at_20_std"],
        "two_tower_trial_count": int(tower["trials"]),
        "lambda_rank_ndcg_at_20": float(ranker["value"]),
        "lambda_rank_trial_count": int(ranker["trials"]),
        "data_source": metrics["data_source"],
    }


def _write_evaluation_reports(run, dataset, before_model, after_model, before, after):
    (run / "personas").mkdir(parents=True, exist_ok=True)
    (run / "plots").mkdir(parents=True, exist_ok=True)

    items = _normalize_items(pd.read_parquet(dataset / "items.parquet"))
    interactions = _normalize_interactions(pd.read_parquet(dataset / "interactions.parquet"))
    item_by_id = {str(row["listing_id"]): row for row in items.to_dict("records")}
    split = temporal_split(interactions.to_dict("records"), key=lambda row: row["event_time"])
    persona_path = dataset / "personas.parquet"
    persona_keys = (
        [str(value) for value in pd.read_parquet(persona_path, columns=["user_key"])["user_key"]]
        if persona_path.is_file()
        else [str(value) for value in interactions["user_key"].unique()]
    )
    personas = _select_personas(
        list(split.train), list(split.feedback), item_by_id, persona_keys
    )
    selected_user_keys = set(personas.values())
    for existing in (run / "personas").iterdir():
        if existing.is_dir() and existing.name not in selected_user_keys:
            shutil.rmtree(existing)
    popularity = Counter(
        str(row["listing_id"]) for row in split.train if int(row.get("gain", 0)) > 0
    )
    popular_ids = [object_id for object_id, _ in popularity.most_common(20)]
    popular_ndcg = _popular_baseline_ndcg(personas.values(), split.test, popular_ids)
    rows = []
    recommendation_lists = {"before": [], "after": []}
    for persona, user_key in personas.items():
        user_directory = run / "personas" / user_key
        user_directory.mkdir(exist_ok=True)
        history = [row for row in split.train if row["user_key"] == user_key]
        test = [row for row in split.test if row["user_key"] == user_key]
        profile = _profile(persona, user_key, history, item_by_id)
        (user_directory / "profile.md").write_text(profile, encoding="utf-8")
        before_result = _evaluate_user(
            before_model, user_key, history, test, item_by_id, popularity
        )
        after_result = _evaluate_user(
            after_model, user_key, history, test, item_by_id, popularity
        )
        (user_directory / "before.json").write_text(
            json.dumps(before_result, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        (user_directory / "after.json").write_text(
            json.dumps(after_result, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        feedback = [row for row in split.feedback if row["user_key"] == user_key]
        (user_directory / "feedback-summary.md").write_text(
            _feedback_summary(feedback), encoding="utf-8"
        )
        for stage, result in (("before", before_result), ("after", after_result)):
            rows.append({"persona": persona, "user_key": user_key, "stage": stage, **result["metrics"]})
            recommendation_lists[stage].extend(
                [item["object_id"] for item in session["recommendations"]]
                for session in result["sessions"]
            )

    catalog = set(item_by_id)
    for stage in ("before", "after"):
        coverage = catalog_coverage(recommendation_lists[stage], catalog)
        for row in rows:
            if row["stage"] == stage:
                row["coverage"] = coverage
    _write_csv(run / "offline-metrics.csv", rows)
    _write_channel_contribution(run / "channel-contribution.csv", before_model, after_model, personas)
    _write_comparison(run, rows, before, after, popular_ndcg)
    _write_plots(run / "plots", rows, interactions, item_by_id, popular_ndcg)
    return items, interactions


def _select_personas(train, feedback, item_by_id, persona_keys):
    by_user: dict[str, list[dict[str, object]]] = defaultdict(list)
    feedback_by_user: dict[str, list[dict[str, object]]] = defaultdict(list)
    for row in train:
        by_user[str(row["user_key"])].append(row)
    for row in feedback:
        feedback_by_user[str(row["user_key"])].append(row)

    def categories(user):
        return [
            item_by_id[str(row["listing_id"])]["category_code"]
            for row in by_user[user]
            if float(row.get("gain", 0)) > 0
        ]

    focus = next(
        (
            user
            for user, rows in sorted(by_user.items())
            if len(rows) >= 10
            and max(Counter(categories(user)).values(), default=0)
            / max(1, len(categories(user)))
            >= 0.6
            and any(_is_purchase(row) for row in feedback_by_user[user])
        ),
        None,
    )
    diverse = max(by_user, key=lambda user: len(set(categories(user))), default=None)
    cart_only = next(
        (
            user
            for user, rows in sorted(by_user.items())
            if any(_is_cart(row) for row in rows)
            and not any(_is_purchase(row) for row in rows)
        ),
        None,
    )
    price_sensitive = min(
        by_user,
        key=lambda user: _price_spread(by_user[user], item_by_id),
        default=None,
    )
    cold = next((user for user in sorted(persona_keys) if user not in by_user), None)
    if cold is None:
        cold = min(by_user, key=lambda user: len(by_user[user]), default=None)
    selected = {
        "重点单一兴趣": focus,
        "多兴趣": diverse,
        "只加购未购买": cart_only,
        "价格敏感": price_sensitive,
        "冷启动": cold,
    }
    used: set[str] = set()
    result = {}
    fallbacks = iter(sorted(by_user, key=lambda user: (len(by_user[user]), user)))
    for persona, user in selected.items():
        while user is None or user in used:
            user = next(fallbacks)
        result[persona] = user
        used.add(user)
    return result


def _evaluate_user(model, user_key, history, test, item_by_id, popularity):
    history_categories = {
        str(item_by_id[str(row["listing_id"])]["category_code"])
        for row in history
        if str(row["listing_id"]) in item_by_id
    }
    sessions = []
    for session_number, session_rows in enumerate(_three_sessions(test), start=1):
        started = time.perf_counter()
        candidates = model.recommend(user_key, 50)
        latency_ms = (time.perf_counter() - started) * 1_000
        relevant = {
            str(row["listing_id"]): float(row["gain"])
            for row in session_rows
            if float(row.get("gain", 0)) > 0
        }
        object_ids = [candidate.object_id for candidate in candidates]
        top_twenty = candidates[:20]
        categories = [
            str(item_by_id[object_id]["category_code"])
            for object_id in object_ids[:20]
            if object_id in item_by_id
        ]
        recommendations = [
            {
                "position": index,
                "object_id": candidate.object_id,
                "title": item_by_id.get(candidate.object_id, {}).get("title"),
                "score": candidate.score,
                "sources": list(candidate.sources),
                "reason_code": candidate.reason_code,
            }
            for index, candidate in enumerate(top_twenty, 1)
        ]
        sessions.append(
            {
                "session": session_number,
                "window_start": _event_boundary(session_rows, first=True),
                "window_end": _event_boundary(session_rows, first=False),
                "held_out_events": len(session_rows),
                "recommendations": recommendations,
                "metrics": {
                    "hit_rate_at_20": hit_rate_at_k(object_ids, set(relevant), 20),
                    "ndcg_at_20": ndcg_at_k(object_ids, relevant, 20),
                    "recall_at_20": recall_at_k(object_ids, set(relevant), 20),
                    "recall_at_50": recall_at_k(object_ids, set(relevant), 50),
                    "category_affinity": sum(
                        category in history_categories for category in categories
                    )
                    / max(1, len(categories)),
                    "intra_list_diversity": _category_diversity(categories),
                    "novelty": _novelty(object_ids, popularity),
                    "latency_ms": latency_ms,
                },
            }
        )
    metric_names = tuple(sessions[0]["metrics"])
    aggregate = {
        name: statistics.fmean(session["metrics"][name] for session in sessions)
        for name in metric_names
    }
    aggregate["latency_p95_ms"] = float(
        pd.Series([session["metrics"]["latency_ms"] for session in sessions]).quantile(0.95)
    )
    return {
        "model_version": model.model_version,
        "session_count": len(sessions),
        "sessions": sessions,
        "metrics": aggregate,
    }


def _three_sessions(rows):
    ordered = sorted(rows, key=lambda row: row["event_time"])
    return [
        ordered[len(ordered) * index // 3 : len(ordered) * (index + 1) // 3]
        for index in range(3)
    ]


def _event_boundary(rows, first):
    if not rows:
        return None
    value = rows[0 if first else -1]["event_time"]
    return value.isoformat() if hasattr(value, "isoformat") else str(value)


def _category_diversity(categories):
    if len(categories) < 2:
        return 0.0
    different = sum(
        left != right
        for index, left in enumerate(categories)
        for right in categories[index + 1 :]
    )
    return different / (len(categories) * (len(categories) - 1) / 2)


def _novelty(object_ids, popularity):
    total = sum(popularity.values())
    if not object_ids or total == 0:
        return 0.0
    return statistics.fmean(
        -math.log2((popularity[object_id] + 1) / (total + len(popularity)))
        for object_id in object_ids
    )


def _popular_baseline_ndcg(user_keys, test_rows, popular_ids):
    by_user = defaultdict(dict)
    for row in test_rows:
        if float(row.get("gain", 0)) > 0:
            by_user[str(row["user_key"])][str(row["listing_id"])] = float(row["gain"])
    return statistics.fmean(
        ndcg_at_k(popular_ids, by_user[str(user_key)], 20) for user_key in user_keys
    )


def _profile(persona, user_key, history, item_by_id):
    positive_history = [row for row in history if float(row.get("gain", 0)) > 0]
    categories = Counter(
        item_by_id[str(row["listing_id"])]["category_code"] for row in positive_history
    )
    authors = Counter(item_by_id[str(row["listing_id"])]["author"] for row in positive_history)
    prices = [
        float(item_by_id[str(row["listing_id"])]["unit_price"]) for row in positive_history
    ]
    return (
        f"# {persona}\n\n"
        f"- 匿名用户：`{user_key}`\n"
        f"- 历史行为数：{len(history)}，有效正反馈数：{len(positive_history)}\n"
        f"- 主要分类：{categories.most_common(5)}\n"
        f"- 主要作者：{authors.most_common(5)}\n"
        f"- 价格区间：{min(prices, default=0):.2f} 至 {max(prices, default=0):.2f}\n"
    )


def _feedback_summary(rows):
    synthetic = any(str(row.get("data_source")) == "SYNTHETIC" for row in rows)
    limitation = (
        "> 该购买信号来自合成旧数据，不等同于真实支付成功。\n"
        if synthetic
        else "> 真实支付与退款事实按订单或 delivery 归因。\n"
    )
    return (
        "# 反馈窗口\n\n"
        f"- 行为数：{len(rows)}\n"
        f"- 加购：{sum(_is_cart(row) for row in rows)}\n"
        f"- 购买/支付信号：{sum(_is_purchase(row) for row in rows)}\n"
        f"- 真实曝光负样本：{sum(row.get('negative_source') == 'REAL_EXPOSURE' for row in rows)}\n"
        f"- 主动退款负反馈：{sum(row.get('negative_source') == 'REAL_REFUND' for row in rows)}\n\n"
        f"{limitation}"
    )


def _is_cart(row):
    return bool(row.get("cart", 0)) or row.get("event_type") == "CART_ADD"


def _is_purchase(row):
    return bool(row.get("legacy_buy_signal", 0)) or row.get("event_type") == "PAYMENT_SUCCEEDED"


def _write_manifest(run, run_id, before, after, before_dataset, after_dataset):
    source_manifest_path = before_dataset / "manifest.json"
    source_manifest = (
        json.loads(source_manifest_path.read_text(encoding="utf-8"))
        if source_manifest_path.is_file()
        else {}
    )
    payload = {
        "run_id": run_id,
        "data_source": source_manifest.get("data_source", "UNKNOWN"),
        "before_dataset": str(before_dataset),
        "after_dataset": str(after_dataset),
        "before_model": before["model_version"],
        "after_model": after["model_version"],
        "split_rows": before["split_rows"],
        "two_tower_trials": before["two_tower_trial_count"],
        "lambda_rank_trials": before["lambda_rank_trial_count"],
        "cuda_device": before["cuda"]["device_name"],
        "cuda_runtime": before["cuda"]["cuda_runtime"],
        "http_feedback_executed": False,
        "quality_claim_allowed": False,
    }
    lines = [f"{key}: {json.dumps(value, ensure_ascii=False)}" for key, value in payload.items()]
    (run / "manifest.yaml").write_text("\n".join(lines) + "\n", encoding="utf-8")


def _write_audit(run, interactions, items, dataset):
    source_manifest_path = dataset / "manifest.json"
    source_manifest = (
        json.loads(source_manifest_path.read_text(encoding="utf-8"))
        if source_manifest_path.is_file()
        else {}
    )
    persona_path = dataset / "personas.parquet"
    data_source = source_manifest.get(
        "data_source",
        str(interactions["data_source"].iloc[0]) if not interactions.empty else "UNKNOWN",
    )
    raw_event_rows = source_manifest.get("rows", {}).get(
        "interactions_raw", source_manifest.get("events", len(interactions))
    )
    statistics_payload = {
        "users": int(interactions["user_key"].nunique()),
        "personas": int(len(pd.read_parquet(persona_path))) if persona_path.is_file() else None,
        "items": int(items["listing_id"].nunique()),
        "raw_events": raw_event_rows,
        "training_samples": len(interactions),
        "minimum_time": interactions["event_time"].min().isoformat(),
        "maximum_time": interactions["event_time"].max().isoformat(),
        "data_source": data_source,
    }
    (run / "dataset-statistics.json").write_text(
        json.dumps(statistics_payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    audit_lines = [
        "# 数据审计",
        "",
        f"- 原始/清洗商品：{source_manifest.get('rows', {}).get('items_raw', len(items))} / {len(items)}",
        f"- 原始事件/训练样本：{raw_event_rows} / {len(interactions)}",
        f"- `rating` 与行为不一致行：{source_manifest.get('rating_behavior_mismatch_rows', '未提供')}",
        f"- 删除的训练字段：{source_manifest.get('removed_training_fields', [])}",
    ]
    if data_source == "SYNTHETIC":
        audit_lines.extend(
            [
                "- 旧购买仅保留为 `legacy_buy_signal`。",
                "- 曝光负样本无法从旧数据恢复；负采样均标记为 `SAMPLED`。",
                "- 本轮仅是合成工程验证。",
            ]
        )
    else:
        audit_lines.extend(
            [
                "- 支付、退款保留为可追溯业务事实。",
                "- 30 分钟未转化曝光标为 `REAL_EXPOSURE`，主动退款标为 `REAL_REFUND`。",
                "- 运营退款不作为兴趣负反馈。",
                "- REAL 数据仍须通过线上实验门槛后才能用于质量声明。",
            ]
        )
    (run / "data-audit.md").write_text(
        "\n".join(audit_lines) + "\n", encoding="utf-8"
    )
    split = temporal_split(
        interactions.to_dict("records"), key=lambda row: pd.to_datetime(row["event_time"], utc=True)
    )
    (run / "split-and-leakage-check.md").write_text(
        "# 时间切分与泄漏检查\n\n"
        f"- 基础训练：前 60%，{len(split.train)} 行，结束于 {_last_time(split.train)}\n"
        f"- 调参与早停：接下来 15%，{len(split.validation)} 行，"
        f"{_first_time(split.validation)} 至 {_last_time(split.validation)}\n"
        f"- 反馈收集：接下来 10%，{len(split.feedback)} 行，"
        f"{_first_time(split.feedback)} 至 {_last_time(split.feedback)}\n"
        f"- 最终测试：最后 15%，{len(split.test)} 行，"
        f"{_first_time(split.test)} 至 {_last_time(split.test)}\n\n"
        "- 双塔和 LambdaRank 只在训练/验证窗口调参；最终测试窗口仅在参数冻结并重训后读取。\n"
        "- 反馈后模型复用已选参数，将反馈窗口并入拟合数据，不在在线进程内微调。\n"
        "- 排序特征在样本更新历史之前计算，不读取样本时刻之后的累计信息。\n"
        "- 本轮反馈来自同一合成快照的时间回放，不等同于已完成真实 HTTP 行为闭环。\n",
        encoding="utf-8",
    )


def _first_time(rows):
    return pd.to_datetime(rows[0]["event_time"], utc=True).isoformat() if rows else "无"


def _last_time(rows):
    return pd.to_datetime(rows[-1]["event_time"], utc=True).isoformat() if rows else "无"


def _write_tuning(path, before, after):
    rows = []
    for stage, result in (("before", before), ("after", after)):
        rows.append(
            {
                "stage": stage,
                "model": "two_tower",
                "metric": "ndcg@20",
                "value": result["two_tower_ndcg_at_20_mean"],
                "parameters": json.dumps(result["two_tower_best_params"]),
                "trials": result["two_tower_trial_count"],
                "elapsed_seconds": result["elapsed_seconds"],
            }
        )
        rows.append(
            {
                "stage": stage,
                "model": "lambda_rank",
                "metric": "ndcg@20",
                "value": result["lambda_rank_ndcg_at_20"],
                "parameters": json.dumps(result["lambda_rank_best_params"]),
                "trials": result["lambda_rank_trial_count"],
                "elapsed_seconds": result["elapsed_seconds"],
            }
        )
    _write_csv(path, rows)


def _write_channel_contribution(path, before_model, after_model, personas):
    rows = []
    for stage, model in (("before", before_model), ("after", after_model)):
        counts = Counter()
        for user_key in personas.values():
            candidates = model.recommend(user_key, 20)
            counts.update(source for candidate in candidates for source in candidate.sources)
        total = sum(counts.values()) or 1
        rows.extend({"stage": stage, "channel": key, "share": value / total} for key, value in sorted(counts.items()))
    _write_csv(path, rows)


def _write_model_card(run, before, after):
    cuda = before["cuda"]
    split_rows = before["split_rows"]
    limitation = (
        "当前数据为 `SYNTHETIC`，旧购买仅是 `legacy_buy_signal`，禁止对外声称线上准确率。"
        if before.get("data_source") == "SYNTHETIC"
        else "当前数据为 `REAL`，仍须通过线上实验与延迟门槛后才能声明推荐质量。"
    )
    (run / "model-card.md").write_text(
        "# Trade 推荐模型卡\n\n"
        f"- 初始模型：`{before['model_version']}`\n"
        f"- 反馈重训模型：`{after['model_version']}`\n"
        f"- GPU：{cuda['device_name']}，显存 {cuda['memory_bytes'] / 1024 ** 3:.2f} GiB\n"
        f"- 训练运行时：PyTorch {cuda['torch_version']} / CUDA {cuda['cuda_runtime']}\n"
        f"- 时间切分行数：训练 {split_rows['train']}、调参 {split_rows['validation']}、"
        f"反馈 {split_rows['feedback']}、最终测试 {split_rows['test']}\n"
        f"- 初始双塔搜索：{before['two_tower_trial_count']} 次；"
        f"三种子 NDCG@20 = {before['two_tower_seed_ndcg_at_20']}，"
        f"均值 {before['two_tower_ndcg_at_20_mean']:.6f}，"
        f"标准差 {before['two_tower_ndcg_at_20_std']:.6f}\n"
        f"- 反馈后双塔：复用已选超参数完整重训；三种子 NDCG@20 = "
        f"{after['two_tower_seed_ndcg_at_20']}，均值 {after['two_tower_ndcg_at_20_mean']:.6f}，"
        f"标准差 {after['two_tower_ndcg_at_20_std']:.6f}\n"
        f"- 初始 LambdaRank 搜索：{before['lambda_rank_trial_count']} 次；"
        f"验证 NDCG@20 = {before['lambda_rank_ndcg_at_20']:.6f}\n"
        f"- 反馈后 LambdaRank：复用已选超参数完整重训；"
        f"验证 NDCG@20 = {after['lambda_rank_ndcg_at_20']:.6f}\n"
        "- 召回：时间衰减热门、新品、TF-IDF、加权 ItemCF、双塔\n"
        "- 精排：LightGBM LambdaRank\n- 重排：MMR、分类/卖家配额、10% 新品探索\n"
        "- 晋级状态：未晋级；尚未取得真实 HTTP 链路延迟证据。\n"
        f"- 限制：{limitation}\n",
        encoding="utf-8",
    )


def _write_comparison(run, rows, before, after, popular_ndcg):
    before_ndcg = sum(row["ndcg_at_20"] for row in rows if row["stage"] == "before") / 5
    after_ndcg = sum(row["ndcg_at_20"] for row in rows if row["stage"] == "after") / 5
    before_coverage = next(row["coverage"] for row in rows if row["stage"] == "before")
    after_coverage = next(row["coverage"] for row in rows if row["stage"] == "after")
    quality_gate = after_ndcg > before_ndcg and after_ndcg > popular_ndcg
    coverage_gate = after_coverage >= before_coverage * 0.9
    promoted = False
    (run / "comparison.md").write_text(
        "# 训练前后比较\n\n"
        f"- 初始模型画像 NDCG@20：{before_ndcg:.6f}\n"
        f"- 反馈后模型画像 NDCG@20：{after_ndcg:.6f}\n"
        f"- 热门基线画像 NDCG@20：{popular_ndcg:.6f}\n"
        f"- 质量门槛：{'通过' if quality_gate else '未通过'}\n"
        f"- 覆盖率门槛：{'通过' if coverage_gate else '未通过'}\n"
        "- HTTP 延迟门槛：未执行，不能判定通过\n"
        f"- 是否提升为候选冠军：{'是' if promoted else '否'}\n\n"
        "> 本轮未取得真实 HTTP 延迟证据，因此即使离线指标改善也不会自动晋级。\n",
        encoding="utf-8",
    )


def _write_plots(directory, rows, interactions, item_by_id, popular_ndcg):
    stages = ["before", "after"]
    ndcg = [
        popular_ndcg,
        *[
            sum(row["ndcg_at_20"] for row in rows if row["stage"] == stage) / 5
            for stage in stages
        ],
    ]
    _bar(
        directory / "recall-ndcg-comparison.png",
        ["popularity", *stages],
        ndcg,
        "NDCG@20",
    )
    coverage = [next(row["coverage"] for row in rows if row["stage"] == stage) for stage in stages]
    diversity = [
        sum(row["intra_list_diversity"] for row in rows if row["stage"] == stage) / 5
        for stage in stages
    ]
    _grouped_bar(directory / "coverage-diversity.png", stages, coverage, diversity)
    if "legacy_buy_signal" in interactions:
        funnel = [
            len(interactions),
            int(interactions["cart"].sum()),
            int(interactions["legacy_buy_signal"].sum()),
        ]
        funnel_labels = ["点击", "加购", "旧购买信号"]
    else:
        funnel = [
            int(interactions["event_type"].eq("IMPRESSION").sum()),
            int(interactions["event_type"].eq("DETAIL_OPEN").sum()),
            int(interactions["event_type"].eq("CART_ADD").sum()),
            int(interactions["event_type"].eq("PAYMENT_SUCCEEDED").sum()),
        ]
        funnel_labels = ["成熟曝光", "详情", "加购", "支付"]
    _bar(directory / "behavior-funnel.png", funnel_labels, funnel, "行为数")
    categories = Counter(item_by_id[str(row["listing_id"])]["category_code"] for row in interactions.to_dict("records"))
    top = categories.most_common(10)
    _bar(directory / "category-affinity.png", [item[0] for item in top], [item[1] for item in top], "交互数")
    latency = [
        max(row["latency_p95_ms"] for row in rows if row["stage"] == stage)
        for stage in stages
    ]
    _bar(
        directory / "latency-percentiles.png",
        stages,
        latency,
        "离线模型调用 P95（毫秒，非 HTTP）",
    )


def _bar(path, labels, values, title):
    figure, axis = plt.subplots(figsize=(8, 4.5))
    axis.bar(labels, values, color="#377D71")
    axis.set_title(title)
    axis.tick_params(axis="x", rotation=25)
    figure.tight_layout()
    figure.savefig(path, dpi=160)
    plt.close(figure)


def _grouped_bar(path, labels, left, right):
    figure, axis = plt.subplots(figsize=(8, 4.5))
    positions = list(range(len(labels)))
    axis.bar([value - 0.18 for value in positions], left, 0.36, label="覆盖率")
    axis.bar([value + 0.18 for value in positions], right, 0.36, label="分类多样性")
    axis.set_xticks(positions, labels)
    axis.legend()
    figure.tight_layout()
    figure.savefig(path, dpi=160)
    plt.close(figure)


def _no_data_plot(path, message):
    figure, axis = plt.subplots(figsize=(8, 4.5))
    axis.axis("off")
    axis.text(0.5, 0.5, message, ha="center", va="center", fontsize=12)
    figure.tight_layout()
    figure.savefig(path, dpi=160)
    plt.close(figure)


def _price_spread(rows, item_by_id):
    prices = [
        float(item_by_id[str(row["listing_id"])]["unit_price"])
        for row in rows
        if float(row.get("gain", 0)) > 0
    ]
    return max(prices, default=0) - min(prices, default=0)


def _write_csv(path, rows):
    if not rows:
        raise ValueError(f"没有可写入的实验数据：{path.name}")
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description="运行 Trade 推荐闭环实验")
    parser.add_argument("--before-dataset", type=Path, required=True)
    parser.add_argument("--after-dataset", type=Path, required=True)
    parser.add_argument("--model-root", type=Path, required=True)
    parser.add_argument("--experiment-root", type=Path, required=True)
    parser.add_argument("--two-tower-trials", type=int, default=30)
    parser.add_argument("--ranker-trials", type=int, default=50)
    arguments = parser.parse_args()
    output = run_experiment(
        arguments.before_dataset,
        arguments.after_dataset,
        arguments.model_root,
        arguments.experiment_root,
        arguments.two_tower_trials,
        arguments.ranker_trials,
    )
    print(output)


if __name__ == "__main__":
    main()
