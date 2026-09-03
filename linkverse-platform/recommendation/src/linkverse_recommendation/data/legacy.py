"""将只读旧 CSV 清洗成隔离的 Trade 合成工程数据集。"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path

import pandas as pd

from linkverse_recommendation.data.audit import find_forbidden_fields


def clean_legacy_dataset(
    items_path: Path,
    users_path: Path,
    interactions_path: Path,
    output: Path,
    hmac_secret: str,
) -> dict[str, object]:
    """清洗旧快照、生成新旧 ID 映射，并写出 Parquet 与审计 manifest。"""

    if len(hmac_secret.encode("utf-8")) < 32:
        raise ValueError("HMAC 密钥至少需要 32 个 UTF-8 字节")
    output.mkdir(parents=True, exist_ok=True)
    items_raw = _read_csv(items_path)
    users_raw = _read_csv(users_path)
    interactions_raw = _read_csv(interactions_path)

    item_mapping = {
        old_id: 20_000 + index
        for index, old_id in enumerate(sorted(items_raw["item_id"].dropna().astype(str).unique()), start=1)
    }
    items = pd.DataFrame(
        {
            "listing_id": items_raw["item_id"].astype(str).map(item_mapping),
            "legacy_item_id": items_raw["item_id"].astype(str),
            "title": items_raw["name"].fillna("").astype(str).str.strip(),
            "author": items_raw["author"].fillna("未知作者").astype(str).str.strip(),
            "description": items_raw["description"].fillna("").astype(str).str.strip(),
            "unit_price": pd.to_numeric(items_raw["price"], errors="coerce"),
            "category_code": items_raw["item_categories"].fillna("UNCLASSIFIED").astype(str).str.strip(),
            "seller_key": items_raw["city"].fillna("SYNTHETIC_UNKNOWN").astype(str).str.strip(),
            "published_at": pd.to_datetime(items_raw["create_time"], errors="coerce"),
            "data_source": "SYNTHETIC",
        }
    )
    invalid_items = items["title"].eq("") | items["unit_price"].isna() | items["unit_price"].le(0)
    items = items.loc[~invalid_items].drop_duplicates("listing_id").sort_values("listing_id")
    valid_listing_ids = set(items["listing_id"].astype(int))

    users = pd.DataFrame(
        {
            "user_key": users_raw["user_id"].astype(str).map(lambda value: _user_key(value, hmac_secret)),
            "legacy_user_id": users_raw["user_id"].astype(str),
            "profile_categories": users_raw["user_categories"].fillna("").astype(str),
            "profile_keywords": users_raw["user_keywords"].fillna("").astype(str),
            "data_source": "SYNTHETIC",
        }
    ).drop_duplicates("user_key")
    user_mapping = dict(zip(users["legacy_user_id"], users["user_key"], strict=True))

    interactions = pd.DataFrame(
        {
            "user_key": interactions_raw["user_id"].astype(str).map(user_mapping),
            "listing_id": interactions_raw["item_id"].astype(str).map(item_mapping),
            "event_time": pd.to_datetime(interactions_raw["datetime"], errors="coerce"),
            "click": _binary(interactions_raw, "click"),
            "cart": _binary(interactions_raw, "cart"),
            "forward": _binary(interactions_raw, "forward"),
            "legacy_buy_signal": _binary(interactions_raw, "buy"),
            "data_source": "SYNTHETIC",
        }
    )
    invalid_interactions = (
        interactions["user_key"].isna()
        | interactions["listing_id"].isna()
        | interactions["event_time"].isna()
        | ~interactions["listing_id"].isin(valid_listing_ids)
    )
    interactions = interactions.loc[~invalid_interactions].copy()
    interactions["listing_id"] = interactions["listing_id"].astype("int64")
    interactions = interactions.drop_duplicates(
        ["user_key", "listing_id", "event_time", "click", "cart", "forward", "legacy_buy_signal"]
    ).sort_values(["event_time", "user_key", "listing_id"])

    items.to_parquet(output / "items.parquet", index=False)
    users.to_parquet(output / "personas.parquet", index=False)
    interactions.to_parquet(output / "interactions.parquet", index=False)
    pd.DataFrame(
        {"legacy_item_id": list(item_mapping), "listing_id": list(item_mapping.values())}
    ).to_parquet(output / "item-id-mapping.parquet", index=False)

    rating_mismatch = int(
        (
            pd.to_numeric(interactions_raw.get("rating"), errors="coerce").fillna(0)
            != interactions_raw[["click", "cart", "forward", "buy"]]
            .fillna(0)
            .astype(int)
            .sum(axis=1)
        ).sum()
    )
    manifest = {
        "schema_version": 1,
        "data_source": "SYNTHETIC",
        "source_hashes": {
            "items": _sha256(items_path),
            "users": _sha256(users_path),
            "interactions": _sha256(interactions_path),
        },
        "rows": {
            "items_raw": len(items_raw),
            "items_clean": len(items),
            "users_raw": len(users_raw),
            "personas_clean": len(users),
            "interactions_raw": len(interactions_raw),
            "interactions_clean": len(interactions),
        },
        "removed_training_fields": sorted(
            set(find_forbidden_fields(items_raw.columns))
            | set(find_forbidden_fields(users_raw.columns))
            | set(find_forbidden_fields(interactions_raw.columns))
        ),
        "rating_behavior_mismatch_rows": rating_mismatch,
        "limitations": [
            "全部交互为合成点击数据，缺少真实曝光负样本",
            "legacy_buy_signal 不等同于 PAYMENT_SUCCEEDED",
            "结果仅用于工程验证，不用于线上质量宣传",
        ],
    }
    (output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return manifest


def _read_csv(path: Path) -> pd.DataFrame:
    frame = pd.read_csv(path, encoding="utf-8-sig")
    return frame.loc[:, ~frame.columns.astype(str).str.startswith("Unnamed")]


def _binary(frame: pd.DataFrame, column: str) -> pd.Series:
    return pd.to_numeric(frame.get(column, 0), errors="coerce").fillna(0).clip(0, 1).astype("int8")


def _user_key(value: str, secret: str) -> str:
    return hmac.new(secret.encode("utf-8"), value.encode("utf-8"), hashlib.sha256).hexdigest()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="清洗旧 Trade 合成数据")
    parser.add_argument("--items", type=Path, required=True)
    parser.add_argument("--users", type=Path, required=True)
    parser.add_argument("--interactions", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--hmac-secret", default=os.environ.get("RECOMMENDATION_USER_HMAC_SECRET", ""))
    arguments = parser.parse_args()
    result = clean_legacy_dataset(
        arguments.items,
        arguments.users,
        arguments.interactions,
        arguments.output,
        arguments.hmac_secret,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
