"""基于隐式反馈权重的 ItemCF 召回。"""

from __future__ import annotations

import math
from collections import defaultdict
from collections.abc import Iterable, Mapping, Sequence

from linkverse_recommendation.pipeline.recall.base import RecallHit


class WeightedItemCF:
    """使用用户内共现、活跃度惩罚和对象度数归一化构建相似度。"""

    def __init__(self) -> None:
        self.similarities: dict[str, dict[str, float]] = {}

    def fit(self, events: Iterable[Mapping[str, object]]) -> "WeightedItemCF":
        user_items: dict[str, dict[str, float]] = defaultdict(dict)
        for event in events:
            user = str(event["user_key"])
            item = str(event["object_id"])
            weight = float(event.get("weight", 1.0))
            user_items[user][item] = max(user_items[user].get(item, 0.0), weight)
        degrees: dict[str, int] = defaultdict(int)
        cooccurrence: dict[str, dict[str, float]] = defaultdict(lambda: defaultdict(float))
        for item_weights in user_items.values():
            items = sorted(item_weights)
            penalty = 1.0 / math.log1p(len(items))
            for left in items:
                degrees[left] += 1
                for right in items:
                    if left != right:
                        cooccurrence[left][right] += penalty * math.sqrt(item_weights[left] * item_weights[right])
        self.similarities = {
            left: {
                right: value / math.sqrt(degrees[left] * degrees[right])
                for right, value in neighbors.items()
            }
            for left, neighbors in cooccurrence.items()
        }
        return self

    def recall(self, history: Sequence[tuple[str, float]], limit: int = 100) -> list[RecallHit]:
        """聚合历史对象邻居，并严格排除用户已有正行为对象。"""

        seen = {item for item, _ in history}
        scores: dict[str, float] = defaultdict(float)
        for item, history_weight in history:
            for neighbor, similarity in self.similarities.get(item, {}).items():
                if neighbor not in seen:
                    scores[neighbor] += history_weight * similarity
        ordered = sorted(scores.items(), key=lambda pair: (-pair[1], pair[0]))[:limit]
        return [RecallHit(item, "ITEM_CF", score, rank) for rank, (item, score) in enumerate(ordered, 1)]
