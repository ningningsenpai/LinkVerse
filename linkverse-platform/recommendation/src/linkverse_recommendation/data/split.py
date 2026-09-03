"""严格按时间线切分训练、调参、反馈和最终测试窗口。"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import TypeVar


T = TypeVar("T")


@dataclass(frozen=True, slots=True)
class TemporalSplit:
    """保存互不重叠的 60/15/10/15 时间窗口。"""

    train: tuple[T, ...]
    validation: tuple[T, ...]
    feedback: tuple[T, ...]
    test: tuple[T, ...]


def temporal_split(rows: Sequence[T], key: Callable[[T], object]) -> TemporalSplit:
    """稳定排序后按索引切分，任何窗口均不会借用未来行。"""

    ordered = sorted(rows, key=key)
    size = len(ordered)
    train_end = int(size * 0.60)
    validation_end = int(size * 0.75)
    feedback_end = int(size * 0.85)
    return TemporalSplit(
        tuple(ordered[:train_end]),
        tuple(ordered[train_end:validation_end]),
        tuple(ordered[validation_end:feedback_end]),
        tuple(ordered[feedback_end:]),
    )
