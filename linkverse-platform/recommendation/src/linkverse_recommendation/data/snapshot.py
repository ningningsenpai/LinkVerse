"""将 Java 只读导出的匿名 JSONL 中间文件固化为版本化 Parquet。"""

from __future__ import annotations

import argparse
import json
import hashlib
from pathlib import Path

import pandas as pd

from linkverse_recommendation.data.audit import duplicate_event_ids


def convert_snapshot(directory: Path) -> None:
    """校验重复事件后写 Parquet，并更新 manifest 阶段和行数。"""

    manifest_path = directory / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    for name in ("objects", "events"):
        actual = hashlib.sha256((directory / f"{name}.jsonl").read_bytes()).hexdigest()
        if actual != manifest.get(f"{name}_sha256"):
            raise ValueError(f"快照中间文件哈希不一致：{name}")
    objects = pd.read_json(directory / "objects.jsonl", lines=True, dtype={"object_id": str})
    events = pd.read_json(directory / "events.jsonl", lines=True, dtype={"object_id": str, "user_key": str})
    duplicates = duplicate_event_ids(events.to_dict("records"))
    if duplicates:
        raise ValueError(f"快照存在重复事件：{sorted(duplicates)[:10]}")
    if len(objects) != manifest["objects"] or len(events) != manifest["events"]:
        raise ValueError("快照行数与 manifest 不一致")
    if objects["object_id"].duplicated().any() or not set(events["object_id"]).issubset(set(objects["object_id"])):
        raise ValueError("快照存在重复商品或悬空行为引用")
    for frame in (objects, events):
        if set(frame["data_source"].dropna()) != {manifest["data_source"]} or frame["data_source"].isna().any():
            raise ValueError("快照包含混合或缺失的数据来源")
        frame["traffic_origin"] = manifest.get("traffic_origin", "UNKNOWN")
    if "recommendation_delivery_id" not in events:
        raise ValueError("行为快照缺少推荐投递 ID，请使用修复后的 Java 导出器")
    objects.to_parquet(directory / "items.parquet", index=False)
    events.to_parquet(directory / "interactions.parquet", index=False)
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
