"""多通道召回的公共结果结构。"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class RecallHit:
    """保留通道原始分数和排名，避免融合时丢失来源信息。"""

    object_id: str
    source: str
    raw_score: float
    rank: int
