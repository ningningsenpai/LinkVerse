"""MMR、类目/卖家配额与确定性新品探索。"""

from __future__ import annotations

from collections import Counter
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class RankedObject:
    """重排所需的对象、相关性和配额字段。"""

    object_id: str
    score: float
    category: str
    seller: str
    is_new: bool
    vector: Sequence[float]


def rerank(
    candidates: Sequence[RankedObject],
    limit: int,
    similarity: Callable[[Sequence[float], Sequence[float]], float],
    mmr_lambda: float = 0.8,
    category_quota: int = 6,
    seller_quota: int = 3,
    exploration_ratio: float = 0.10,
) -> list[RankedObject]:
    """只处理前 50 个候选，在配额内平衡相关性、多样性与新品探索。"""

    pool = list(candidates[:50])
    selected: list[RankedObject] = []
    categories: Counter[str] = Counter()
    sellers: Counter[str] = Counter()
    maximum_similarity = {item.object_id: 0.0 for item in pool}
    exploration_target = max(1, round(limit * exploration_ratio)) if pool else 0
    while pool and len(selected) < limit:
        eligible = [
            item
            for item in pool
            if categories[item.category] < category_quota and sellers[item.seller] < seller_quota
        ]
        if not eligible:
            break
        need_new = sum(item.is_new for item in selected) < exploration_target
        exploration = [item for item in eligible if item.is_new] if need_new else []
        choices = exploration or eligible
        best = max(
            choices,
            key=lambda item: (
                mmr_lambda * item.score
                - (1 - mmr_lambda)
                * maximum_similarity[item.object_id],
                item.object_id,
            ),
        )
        selected.append(best)
        pool.remove(best)
        for remaining in pool:
            maximum_similarity[remaining.object_id] = max(
                maximum_similarity[remaining.object_id],
                similarity(remaining.vector, best.vector),
            )
        categories[best.category] += 1
        sellers[best.seller] += 1
    return selected
