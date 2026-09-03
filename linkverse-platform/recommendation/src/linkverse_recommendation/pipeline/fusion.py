"""保留通道证据的归一化加权与 RRF 融合。"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from linkverse_recommendation.pipeline.recall.base import RecallHit


@dataclass(frozen=True, slots=True)
class FusedCandidate:
    """保存融合总分及各通道原始分、排名和来源。"""

    object_id: str
    score: float
    sources: tuple[str, ...]
    channel_scores: Mapping[str, float]
    channel_ranks: Mapping[str, int]


def fuse_channels(
    channels: Mapping[str, Sequence[RecallHit]],
    weights: Mapping[str, float] | None = None,
    rrf_k: int = 60,
    limit: int = 300,
) -> list[FusedCandidate]:
    """通道内做 min-max 归一化，再叠加加权分和倒数排名证据。"""

    weights = weights or {}
    totals: dict[str, float] = defaultdict(float)
    raw_scores: dict[str, dict[str, float]] = defaultdict(dict)
    ranks: dict[str, dict[str, int]] = defaultdict(dict)
    for source, hits in channels.items():
        if not hits:
            continue
        values = [hit.raw_score for hit in hits]
        minimum, maximum = min(values), max(values)
        scale = maximum - minimum
        channel_weight = weights.get(source, 1.0)
        for hit in hits:
            normalized = (hit.raw_score - minimum) / scale if scale else 1.0
            totals[hit.object_id] += channel_weight * (normalized + 1.0 / (rrf_k + hit.rank))
            raw_scores[hit.object_id][source] = hit.raw_score
            ranks[hit.object_id][source] = hit.rank
    ordered = sorted(totals, key=lambda object_id: (-totals[object_id], object_id))[:limit]
    return [
        FusedCandidate(
            object_id,
            totals[object_id],
            tuple(sorted(raw_scores[object_id])),
            dict(raw_scores[object_id]),
            dict(ranks[object_id]),
        )
        for object_id in ordered
    ]
