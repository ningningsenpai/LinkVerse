"""把 Trade 原始事实事件转换为可按时间切分的会话级训练样本。"""

from __future__ import annotations

from datetime import timedelta

import pandas as pd


GAINS = {
    "IMPRESSION": 0,
    "DETAIL_OPEN": 1,
    "CART_ADD": 3,
    "ORDER_CREATED": 7,
    "PAYMENT_SUCCEEDED": 15,
}
NON_PREFERENCE_REFUNDS = {"LATE_SUCCESS", "PAYMENT_TIMEOUT", "SYSTEM_COMPENSATION"}


def build_trade_training_samples(events: pd.DataFrame) -> pd.DataFrame:
    """按归因链合并事件；未成熟曝光不入样本，主动退款覆盖同链正反馈。"""

    if events.empty:
        result = events.copy()
        result["gain"] = pd.Series(dtype="int8")
        result["preference_negative"] = pd.Series(dtype="bool")
        result["negative_source"] = pd.Series(dtype="object")
        return result
    required = {"event_id", "user_key", "object_id", "event_type", "event_time", "ingested_at"}
    missing = sorted(required - set(events.columns))
    if missing:
        raise ValueError(f"行为快照缺少字段：{missing}")
    normalized = events.copy()
    normalized["event_time"] = pd.to_datetime(normalized["event_time"], utc=True)
    normalized["ingested_at"] = pd.to_datetime(normalized["ingested_at"], utc=True)
    if "refund_reason_code" not in normalized:
        normalized["refund_reason_code"] = None
    normalized["_attribution_key"] = normalized.apply(_attribution_key, axis=1)
    maturity_cutoff = normalized["ingested_at"].max() - timedelta(minutes=30)
    samples = []
    group_fields = ["user_key", "object_id", "_attribution_key"]
    for _, group in normalized.groupby(group_fields, sort=False, dropna=False):
        ordered = group.sort_values(["event_time", "ingested_at", "event_id"])
        preference_refunds = ordered[
            ordered["event_type"].eq("REFUNDED")
            & ~ordered["refund_reason_code"].isin(NON_PREFERENCE_REFUNDS)
        ]
        if not preference_refunds.empty:
            samples.append(_sample(preference_refunds.iloc[-1], 0, True, "REAL_REFUND"))
            continue
        positive = ordered[ordered["event_type"].isin(set(GAINS) - {"IMPRESSION"})].copy()
        if not positive.empty:
            positive["_gain"] = positive["event_type"].map(GAINS)
            selected = positive.sort_values(["_gain", "event_time", "ingested_at"]).iloc[-1]
            samples.append(_sample(selected, int(selected["_gain"]), False, None))
            continue
        matured = ordered[
            ordered["event_type"].eq("IMPRESSION") & ordered["event_time"].le(maturity_cutoff)
        ]
        if not matured.empty:
            samples.append(_sample(matured.iloc[-1], 0, False, "REAL_EXPOSURE"))
    result = pd.DataFrame(samples)
    if result.empty:
        return normalized.iloc[0:0].drop(columns=["_attribution_key"]).assign(
            gain=pd.Series(dtype="int8"),
            preference_negative=pd.Series(dtype="bool"),
            negative_source=pd.Series(dtype="object"),
        )
    return result.sort_values(["event_time", "event_id"]).reset_index(drop=True)


def _attribution_key(row: pd.Series) -> str:
    for field, prefix in (
        ("recommendation_delivery_id", "delivery"),
        ("session_id", "session"),
        ("order_no", "order"),
    ):
        value = row.get(field)
        if pd.notna(value) and str(value).strip():
            return f"{prefix}:{value}"
    return f"event:{row['event_id']}"


def _sample(row: pd.Series, gain: int, preference_negative: bool, negative_source: str | None):
    result = row.drop(labels=["_attribution_key", "_gain"], errors="ignore").to_dict()
    result["gain"] = gain
    result["preference_negative"] = preference_negative
    result["negative_source"] = negative_source
    return result
