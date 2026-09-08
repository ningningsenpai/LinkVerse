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
    """在完整的有界候选池内平衡相关性、多样性与新品探索，配额不足时返回短列表。"""

    pool = list(candidates[:500])
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


def rerank_with_matrix(candidates: Sequence[RankedObject], limit: int, similarities) -> list[RankedObject]:
    """复用已计算的相似度矩阵，以相同默认策略和并列规则减少逐候选 Python 循环。"""
    import numpy as np

    pool = list(candidates[:500])
    if not pool or limit <= 0:
        return []
    if similarities.shape != (len(pool), len(pool)):
        raise ValueError("相似度矩阵与候选数量不一致")
    categories = {name: index for index, name in enumerate(sorted({item.category for item in pool}))}
    sellers = {name: index for index, name in enumerate(sorted({item.seller for item in pool}))}
    category_ids = np.asarray([categories[item.category] for item in pool])
    seller_ids = np.asarray([sellers[item.seller] for item in pool])
    category_counts = np.zeros(len(categories), dtype=np.int64)
    seller_counts = np.zeros(len(sellers), dtype=np.int64)
    available = np.ones(len(pool), dtype=bool)
    new_items = np.asarray([item.is_new for item in pool], dtype=bool)
    scores = np.asarray([item.score for item in pool], dtype=np.float64)
    ranks = {name: index for index, name in enumerate(sorted(item.object_id for item in pool))}
    tie_ranks = np.asarray([ranks[item.object_id] for item in pool])
    maximum_similarity = np.zeros(len(pool), dtype=np.float64)
    exploration_target = max(1, round(limit * .10))
    selected, new_count = [], 0
    while len(selected) < limit:
        eligible = available & (category_counts[category_ids] < 6) & (seller_counts[seller_ids] < 3)
        exploration = eligible & new_items
        if new_count < exploration_target and exploration.any():
            eligible = exploration
        indexes = np.flatnonzero(eligible)
        if not len(indexes):
            break
        values = .8 * scores[indexes] - (1 - .8) * maximum_similarity[indexes]
        tied = indexes[values == values.max()]
        best = int(tied[np.argmax(tie_ranks[tied])])
        selected.append(pool[best])
        available[best] = False
        new_count += int(new_items[best])
        category_counts[category_ids[best]] += 1
        seller_counts[seller_ids[best]] += 1
        np.maximum(maximum_similarity, similarities[:, best], out=maximum_similarity)
    return selected
