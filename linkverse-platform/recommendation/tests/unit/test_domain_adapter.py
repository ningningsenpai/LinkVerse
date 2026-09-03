from datetime import datetime, timezone

from linkverse_recommendation.core.events import BehaviorEvent, BehaviorType
from linkverse_recommendation.domains.trade import TradeDomainAdapter


def event(event_type, reason=None):
    now = datetime.now(timezone.utc)
    return BehaviorEvent("e", "u", "i", event_type, now, now, refund_reason_code=reason)


def test_trade_gain_uses_highest_behavior_and_ignores_operational_refund():
    adapter = TradeDomainAdapter()

    gain = adapter.label_gain(
        [event(BehaviorType.DETAIL_OPEN), event(BehaviorType.PAYMENT_SUCCEEDED), event(BehaviorType.REFUNDED, "LATE_SUCCESS")]
    )

    assert gain == 15


def test_user_initiated_refund_becomes_negative_feedback():
    adapter = TradeDomainAdapter()

    assert adapter.label_gain(
        [event(BehaviorType.REFUNDED, "QUALITY_ISSUE"), event(BehaviorType.PAYMENT_SUCCEEDED)]
    ) == -3


def test_deep_model_gate_requires_real_scale_and_degree():
    assert TradeDomainAdapter().deep_model_ready([event(BehaviorType.PAYMENT_SUCCEEDED)]) is False
