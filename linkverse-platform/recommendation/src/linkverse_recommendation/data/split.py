"""严格按时间线切分训练、调参、反馈和最终测试窗口。"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import TypeVar
import json
from pathlib import Path


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


def fixed_temporal_split(rows: Sequence[dict], manifest_path: Path) -> TemporalSplit:
    """首次固化绝对时间边界，后续数据只按原边界分配，拒绝跨窗口归因链。"""
    import pandas as pd

    ordered = sorted(rows, key=lambda row: row["event_time"])
    if len(ordered) < 8:
        raise ValueError("固定时间切分至少需要 8 个训练样本")
    times = [pd.to_datetime(row["event_time"], utc=True) for row in ordered]
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    else:
        boundaries = [times[int(len(times) * ratio)].isoformat() for ratio in (.60, .75, .85)]
        manifest = {"schema_version": 1, "boundaries": boundaries, "end_inclusive": times[-1].isoformat(), "purpose": "HISTORICAL_REGRESSION"}
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    boundaries = [pd.to_datetime(value, utc=True) for value in manifest["boundaries"]]
    if len(boundaries) != 3 or not boundaries[0] < boundaries[1] < boundaries[2]:
        raise ValueError("时间切分边界必须严格递增")
    end = pd.to_datetime(manifest["end_inclusive"], utc=True)
    windows = [[], [], [], []]
    chains: dict[tuple, int] = {}
    purged = 0
    for row, occurred in zip(ordered, times, strict=True):
        if occurred > end:
            raise ValueError("新增数据超出冻结切分范围，请建立独立运行而不是重切旧测试集")
        index = sum(occurred >= boundary for boundary in boundaries)
        start = pd.to_datetime(row.get("attribution_start_time", occurred), utc=True)
        available = pd.to_datetime(row.get("label_available_at", occurred), utc=True)
        window_end = boundaries[index] if index < 3 else end
        if sum(start >= boundary for boundary in boundaries) != index or available > end or (index < 3 and available >= window_end):
            purged += 1
            continue
        chain = row.get("attribution_key")
        if chain is not None:
            chain_key = (str(row["user_key"]), str(row["object_id"]), str(chain))
            if chain_key in chains and chains[chain_key] != index:
                raise ValueError("同一归因链跨越训练与评估窗口")
            chains[chain_key] = index
        windows[index].append(row)
    if any(not window for window in windows):
        raise ValueError("冻结切分包含空窗口，不能执行训练与比较")
    from linkverse_recommendation.training.recording import record
    record("split", rows=len(rows), purged_cross_window_labels=purged, windows=[len(window) for window in windows])
    return TemporalSplit(*(tuple(window) for window in windows))
