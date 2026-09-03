"""将 Java 只读导出的匿名 JSONL 中间文件固化为版本化 Parquet。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import pandas as pd

from linkverse_recommendation.data.audit import duplicate_event_ids


def convert_snapshot(directory: Path) -> None:
    """校验重复事件后写 Parquet，并更新 manifest 阶段和行数。"""

    objects = pd.read_json(directory / "objects.jsonl", lines=True)
    events = pd.read_json(directory / "events.jsonl", lines=True)
    duplicates = duplicate_event_ids(events.to_dict("records"))
    if duplicates:
        raise ValueError(f"快照存在重复事件：{sorted(duplicates)[:10]}")
    objects.to_parquet(directory / "items.parquet", index=False)
    events.to_parquet(directory / "interactions.parquet", index=False)
    manifest_path = directory / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["stage"] = "PARQUET_READY"
    manifest["parquet_rows"] = {"items": len(objects), "interactions": len(events)}
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="将 Trade 匿名快照转换为 Parquet")
    parser.add_argument("--snapshot", type=Path, required=True)
    arguments = parser.parse_args()
    convert_snapshot(arguments.snapshot)


if __name__ == "__main__":
    main()
