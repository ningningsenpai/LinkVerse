"""数据质量审计与未来泄漏检查。"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence


FORBIDDEN_TRAINING_FIELDS = {
    "rating",
    "last3m",
    "user_click_last3m",
    "user_cart_last3m",
    "user_buy_last3m",
    "user_forward_last3m",
    "item_click_last3m",
    "item_cart_last3m",
    "item_buy_last3m",
    "item_forward_last3m",
}


def find_forbidden_fields(columns: Iterable[str]) -> list[str]:
    """找出不能进入训练 Schema 的评分与聚合字段。"""

    return sorted({column for column in columns if column in FORBIDDEN_TRAINING_FIELDS or "last3m" in column})


def assert_no_future_features(
    samples: Sequence[Mapping[str, object]],
    feature_cutoff_field: str = "feature_cutoff",
    event_time_field: str = "event_time",
) -> None:
    """拒绝特征截止时点晚于样本发生时点的记录。"""

    for index, sample in enumerate(samples):
        cutoff = sample.get(feature_cutoff_field)
        occurred = sample.get(event_time_field)
        if cutoff is not None and occurred is not None and cutoff > occurred:
            raise ValueError(f"第 {index} 条样本使用了事件之后的特征")


def duplicate_event_ids(rows: Iterable[Mapping[str, object]]) -> set[str]:
    """返回重复事件标识，供快照导入在训练前阻断。"""

    seen: set[str] = set()
    duplicates: set[str] = set()
    for row in rows:
        event_id = str(row.get("event_id") or "")
        if not event_id:
            continue
        if event_id in seen:
            duplicates.add(event_id)
        seen.add(event_id)
    return duplicates
