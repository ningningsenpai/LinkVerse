"""推荐离线评估指标。"""

from __future__ import annotations

import math
from collections.abc import Iterable, Mapping, Sequence


def recall_at_k(recommended: Sequence[str], relevant: set[str], k: int) -> float:
    """计算前 K 个结果覆盖真实相关对象的比例。"""

    if not relevant:
        return 0.0
    return len(set(recommended[:k]) & relevant) / len(relevant)


def hit_rate_at_k(recommended: Sequence[str], relevant: set[str], k: int) -> float:
    """判断前 K 个结果是否至少命中一个真实相关对象。"""

    return float(bool(set(recommended[:k]) & relevant))


def ndcg_at_k(recommended: Sequence[str], gains: Mapping[str, float], k: int) -> float:
    """按业务增益计算归一化折损累计增益。"""

    dcg = sum((2 ** gains.get(item, 0.0) - 1) / math.log2(index + 2) for index, item in enumerate(recommended[:k]))
    ideal = sorted(gains.values(), reverse=True)[:k]
    ideal_dcg = sum((2**gain - 1) / math.log2(index + 2) for index, gain in enumerate(ideal))
    return dcg / ideal_dcg if ideal_dcg else 0.0


def grouped_tie_aware_ndcg(predictions, labels, groups, k: int = 20) -> float:
    """对同分对象的所有排列取期望增益，避免正例原始位置影响精排选参。"""
    import numpy as np

    scores = np.asarray(predictions, dtype=np.float64)
    gains = np.exp2(np.asarray(labels, dtype=np.float64)) - 1
    sizes = np.asarray(groups, dtype=np.int64)
    if not len(sizes) or k <= 0:
        return 0.0
    if np.any(sizes <= 0) or sizes.sum() != len(scores) or len(scores) != len(gains):
        raise ValueError("精排指标分组与预测数量不一致")
    offsets = np.cumsum(np.concatenate(([0], sizes[:-1])))
    values = []
    # 分块计算限制临时矩阵大小，既覆盖当前小组，也支持后续真实候选组。
    for start in range(0, len(sizes), 1024):
        batch_sizes = sizes[start:start + 1024]
        width = int(batch_sizes.max())
        positions = np.arange(width)[None, :]
        valid = positions < batch_sizes[:, None]
        indexes = np.minimum(offsets[start:start + 1024, None] + positions, len(scores) - 1)
        batch_scores = np.where(valid, scores[indexes], -np.inf)
        batch_gains = np.where(valid, gains[indexes], 0.0)
        order = np.argsort(-batch_scores, axis=1, kind="stable")
        ordered_scores = np.take_along_axis(batch_scores, order, axis=1)
        ordered_gains = np.take_along_axis(batch_gains, order, axis=1)
        beginnings = np.ones_like(ordered_scores, dtype=bool)
        beginnings[:, 1:] = ordered_scores[:, 1:] != ordered_scores[:, :-1]
        segments = np.cumsum(beginnings.ravel()) - 1
        totals = np.bincount(segments, weights=ordered_gains.ravel())
        counts = np.bincount(segments)
        expected_gains = (totals[segments] / counts[segments]).reshape(ordered_gains.shape)
        cutoff = min(k, width)
        discounts = 1 / np.log2(np.arange(cutoff) + 2)
        dcg = expected_gains[:, :cutoff] @ discounts
        ideal = -np.sort(-batch_gains, axis=1)[:, :cutoff] @ discounts
        values.extend(np.divide(dcg, ideal, out=np.zeros_like(dcg), where=ideal > 0).tolist())
    return float(np.mean(values))


def catalog_coverage(recommendations: Iterable[Sequence[str]], catalog: set[str]) -> float:
    """计算所有列表触达的目录对象比例。"""

    if not catalog:
        return 0.0
    exposed = {item for result in recommendations for item in result}
    return len(exposed & catalog) / len(catalog)


def intra_list_diversity(vectors: Sequence[Sequence[float]]) -> float:
    """使用平均余弦距离衡量单列表多样性。"""

    if len(vectors) < 2:
        return 0.0
    distances: list[float] = []
    for left_index, left in enumerate(vectors):
        for right in vectors[left_index + 1 :]:
            numerator = sum(a * b for a, b in zip(left, right, strict=True))
            left_norm = math.sqrt(sum(value * value for value in left))
            right_norm = math.sqrt(sum(value * value for value in right))
            similarity = numerator / (left_norm * right_norm) if left_norm and right_norm else 0.0
            distances.append(1.0 - similarity)
    return sum(distances) / len(distances)
