"""Trade 对象、标签与深度模型启用门槛。"""

from __future__ import annotations

from collections import Counter
from collections.abc import Iterable, Mapping
from decimal import Decimal, InvalidOperation

from linkverse_recommendation.core.events import BehaviorEvent, BehaviorType
from linkverse_recommendation.domains.base import DomainAdapter


GAINS = {
    BehaviorType.IMPRESSION: 0,
    BehaviorType.DETAIL_OPEN: 1,
    BehaviorType.CART_ADD: 3,
    BehaviorType.ORDER_CREATED: 7,
    BehaviorType.PAYMENT_SUCCEEDED: 15,
}

# 运营补偿不表示用户不感兴趣，训练时只保留事实而不降权。
NON_PREFERENCE_REFUNDS = {"LATE_SUCCESS", "PAYMENT_TIMEOUT", "SYSTEM_COMPENSATION"}


class TradeDomainAdapter(DomainAdapter):
    """实现 Trade 独有的商品规范化、标签和数据量门槛。"""

    @property
    def name(self) -> str:
        return "trade"

    def normalize_object(self, raw: Mapping[str, object]) -> dict[str, object]:
        object_id = str(raw.get("object_id") or raw.get("item_id") or "").strip()
        title = str(raw.get("title") or raw.get("book_name") or "").strip()
        if not object_id or not title:
            raise ValueError("商品缺少稳定标识或标题")
        try:
            price = Decimal(str(raw.get("price") or raw.get("unit_price")))
        except (InvalidOperation, TypeError) as exception:
            raise ValueError("商品价格格式不正确") from exception
        if price <= 0:
            raise ValueError("商品价格必须大于零")
        return {
            "object_id": object_id,
            "title": title,
            "author": str(raw.get("author") or "未知作者").strip(),
            "description": str(raw.get("description") or "").strip(),
            "category_code": str(raw.get("category_code") or raw.get("category") or "UNCLASSIFIED").strip(),
            "price": str(price.quantize(Decimal("0.0001"))),
        }

    def label_gain(self, events: Iterable[BehaviorEvent]) -> int:
        gain = 0
        preference_refund = False
        for event in events:
            if event.event_type == BehaviorType.REFUNDED:
                if event.refund_reason_code not in NON_PREFERENCE_REFUNDS:
                    preference_refund = True
                continue
            gain = max(gain, GAINS.get(event.event_type, 0))
        return -3 if preference_refund else gain

    def deep_model_ready(self, events: Iterable[BehaviorEvent]) -> bool:
        materialized = [event for event in events if GAINS.get(event.event_type, 0) > 0]
        if len(materialized) < 100_000:
            return False
        user_degrees = Counter(event.user_key for event in materialized)
        object_degrees = Counter(event.object_id for event in materialized)
        return _majority_at_least_three(user_degrees) and _majority_at_least_three(object_degrees)


def _majority_at_least_three(degrees: Counter[str]) -> bool:
    if not degrees:
        return False
    return sum(value >= 3 for value in degrees.values()) / len(degrees) >= 0.8
