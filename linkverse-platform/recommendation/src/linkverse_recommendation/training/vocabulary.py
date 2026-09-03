"""保留独立 OOV 的稳定词表。"""

from __future__ import annotations

import json
from collections.abc import Iterable
from pathlib import Path


OOV_INDEX = 0


def build_vocabulary(values: Iterable[str]) -> dict[str, int]:
    """排序后从 1 分配索引，0 永远只用于 OOV。"""

    return {value: index for index, value in enumerate(sorted(set(values)), start=1)}


def encode(value: str, vocabulary: dict[str, int]) -> int:
    return vocabulary.get(value, OOV_INDEX)


def save_vocabulary(path: Path, vocabulary: dict[str, int]) -> None:
    path.write_text(json.dumps(vocabulary, ensure_ascii=False, sort_keys=True), encoding="utf-8")


def load_vocabulary(path: Path) -> dict[str, int]:
    vocabulary = json.loads(path.read_text(encoding="utf-8"))
    indexes = sorted(vocabulary.values())
    if indexes != list(range(1, len(indexes) + 1)):
        raise ValueError("词表必须连续从 1 编号，0 仅用于 OOV")
    return vocabulary
