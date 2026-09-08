"""训练和在线精排共用的历史特征，状态只接受已可用的反馈。"""

from __future__ import annotations

import math
from collections import Counter
from datetime import datetime


HISTORY_FEATURES = [
    "prior_popularity_count_ratio", "category_affinity", "author_affinity",
    "price_distance", "freshness",
]
RECALL_CHANNELS = ("TWO_TOWER", "ITEM_CF", "TFIDF", "POPULARITY", "NEW")
RECALL_FEATURES = HISTORY_FEATURES + [
    "tower_similarity", "known_user", "fusion_score", "has_fusion_score",
    "popular_reciprocal_rank", *[f"source_{name.lower()}" for name in RECALL_CHANNELS],
]


def recall_features(candidate: dict, tower_score: float, known_user: bool, popular_rank: int) -> list[float]:
    """补位对象缺失融合分数时显式标记，不将旧精排分数冒充召回相关性。"""
    return [
        float(tower_score), float(known_user), float(candidate.get("recall_score", 0)),
        float("recall_score" in candidate), 1 / popular_rank if popular_rank else 0.0,
        *[float(channel in candidate["sources"]) for channel in RECALL_CHANNELS],
    ]


def relevance_scores(scores, policy: str):
    """按请求校准 MMR 相关性，同分取平均名次且保留并列关系。"""
    import numpy as np

    values = np.asarray(scores, dtype=np.float64)
    if not np.isfinite(values).all():
        raise ValueError("精排分数必须为有限值")
    if policy == "raw":
        return values
    if policy != "percentile":
        raise ValueError("未知的重排分数策略")
    if len(values) <= 1:
        return np.full_like(values, .5)
    _, inverse, counts = np.unique(values, return_inverse=True, return_counts=True)
    ends = np.cumsum(counts)
    midranks = (ends - counts + ends - 1) / 2
    return midranks[inverse] / (len(values) - 1)


def empty_state(items: dict[str, dict]) -> dict:
    return {"items": items, "users": {}, "popularity": {}, "maximum_popularity": 1}


def observe(state: dict, row: dict) -> None:
    object_id, user_key = str(row["object_id"]), str(row["user_key"])
    if object_id not in state["items"]:
        return
    user = state["users"].setdefault(user_key, {"positive": {}, "negative": []})
    if row.get("negative_source") in {"REAL_EXPOSURE", "REAL_REFUND"}:
        if object_id not in user["negative"]:
            user["negative"].append(object_id)
        if row.get("preference_negative"):
            user["positive"].pop(object_id, None)
    if row["gain"] > 0:
        user["positive"][object_id] = user["positive"].get(object_id, 0) + 1
        if object_id in user["negative"]:
            user["negative"].remove(object_id)
        state["popularity"][object_id] = state["popularity"].get(object_id, 0) + 1
        state["maximum_popularity"] = max(state["maximum_popularity"], state["popularity"][object_id])


def profile(state: dict, user_key: str, context: dict | None = None) -> dict:
    positives = dict(state["users"].get(user_key, {}).get("positive", {}))
    for object_id in (context or {}).get("recent_positive_ids", "").split(","):
        if object_id in state["items"] and object_id not in positives:
            positives[object_id] = 1
    categories, authors = Counter(), Counter()
    total, log_price = 0, 0.0
    for object_id, count in positives.items():
        item = state["items"][object_id]
        categories[item["category_code"]] += count
        authors[item["author"]] += count
        log_price += math.log1p(item["unit_price"]) * count
        total += count
    context_id = (context or {}).get("object_id")
    if context_id in state["items"]:
        item = state["items"][context_id]
        categories[item["category_code"]] += 1
        authors[item["author"]] += 1
        log_price += math.log1p(item["unit_price"])
        total += 1
    return {"categories": categories, "authors": authors, "count": max(1, total), "log_price": log_price / total if total else math.log1p(50)}


def rank_features(state: dict, user_profile: dict, object_id: str, now: datetime) -> list[float]:
    item = state["items"][object_id]
    published = datetime.fromisoformat(item["published_at"])
    age_days = max(0.0, (now - published).total_seconds() / 86400)
    return [
        state["popularity"].get(object_id, 0) / state["maximum_popularity"],
        user_profile["categories"].get(item["category_code"], 0) / user_profile["count"],
        user_profile["authors"].get(item["author"], 0) / user_profile["count"],
        abs(math.log1p(item["unit_price"]) - user_profile["log_price"]),
        math.exp(-age_days / 30),
    ]


def catalog_from_frame(items) -> dict[str, dict]:
    return {str(row["listing_id"]): {
        "category_code": str(row["category_code"]), "author": str(row["author"]),
        "seller_key": str(row.get("seller_key", "UNKNOWN")),
        "unit_price": float(row["unit_price"]), "published_at": row["published_at"].isoformat(),
    } for row in items.to_dict("records")}
