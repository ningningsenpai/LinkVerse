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
