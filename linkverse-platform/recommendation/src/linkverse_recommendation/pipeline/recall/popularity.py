"""时间衰减热门与新对象召回。"""

from __future__ import annotations

import math
from collections import defaultdict
from collections.abc import Iterable, Mapping, Sequence
from datetime import datetime

from linkverse_recommendation.pipeline.recall.base import RecallHit


def time_decay_popularity(
    events: Iterable[Mapping[str, object]],
    now: datetime,
    half_life_days: float = 14.0,
    limit: int = 100,
) -> list[RecallHit]:
    """按行为权重和指数时间衰减计算热门召回。"""

    scores: dict[str, float] = defaultdict(float)
    weights = {
        "IMPRESSION": 0.2,
        "DETAIL_OPEN": 1.0,
        "CART_ADD": 3.0,
        "ORDER_CREATED": 7.0,
        "LEGACY_BUY_SIGNAL": 7.0,
        "PAYMENT_SUCCEEDED": 15.0,
        "REFUNDED": 0.0,
    }
    for event in events:
        occurred = event["event_time"]
        age_days = max(0.0, (now - occurred).total_seconds() / 86_400)
        scores[str(event["object_id"])] += weights.get(str(event.get("event_type")), 0.0) * math.exp(
            -math.log(2) * age_days / half_life_days
        )
    ordered = sorted(scores.items(), key=lambda pair: (-pair[1], pair[0]))[:limit]
    return [RecallHit(object_id, "POPULARITY", score, rank) for rank, (object_id, score) in enumerate(ordered, 1)]


def newest(objects: Sequence[Mapping[str, object]], limit: int = 100) -> list[RecallHit]:
    """按发布时间稳定排序生成新对象通道。"""

    ordered = sorted(objects, key=lambda row: (row["published_at"], str(row["object_id"])), reverse=True)[:limit]
    return [RecallHit(str(row["object_id"]), "NEW", 1.0 / rank, rank) for rank, row in enumerate(ordered, 1)]
