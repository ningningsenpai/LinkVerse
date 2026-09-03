"""跨领域共享的行为事件和值对象。"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum


class DataSource(StrEnum):
    """区分真实闭环与合成工程验证数据。"""

    REAL = "REAL"
    SYNTHETIC = "SYNTHETIC"


class BehaviorType(StrEnum):
    """公共行为等级；领域适配器负责解释标签。"""

    IMPRESSION = "IMPRESSION"
    DETAIL_OPEN = "DETAIL_OPEN"
    CART_ADD = "CART_ADD"
    ORDER_CREATED = "ORDER_CREATED"
    PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED"
    REFUNDED = "REFUNDED"


@dataclass(frozen=True, slots=True)
class BehaviorEvent:
    """不可变事件，所有累计特征只能读取 ``event_time`` 之前的事件。"""

    event_id: str
    user_key: str
    object_id: str
    event_type: BehaviorType
    event_time: datetime
    ingested_at: datetime
    request_id: str | None = None
    session_id: str | None = None
    position: int | None = None
    source: str | None = None
    model_version: str | None = None
    order_no: str | None = None
    refund_reason_code: str | None = None
    schema_version: int = 1
    data_source: DataSource = DataSource.REAL
