from datetime import datetime, timedelta, timezone

import pytest
import pandas as pd

from linkverse_recommendation.data.audit import assert_no_future_features, find_forbidden_fields
from linkverse_recommendation.data.labels import build_trade_training_samples
from linkverse_recommendation.data.negative_sampling import sample_negatives
from linkverse_recommendation.data.split import temporal_split
from linkverse_recommendation.training.trainer import _training_tensors, _validate_data_source


def test_temporal_split_preserves_60_15_10_15_order():
    rows = [{"time": index} for index in reversed(range(100))]

    result = temporal_split(rows, key=lambda row: row["time"])

    assert [len(result.train), len(result.validation), len(result.feedback), len(result.test)] == [60, 15, 10, 15]
    assert result.train[-1]["time"] < result.validation[0]["time"]
    assert result.feedback[-1]["time"] < result.test[0]["time"]


def test_negative_sampling_never_uses_user_positive_and_is_reproducible():
    positives = {"u1": {"i1", "i2"}, "u2": {"i2"}}

    first = sample_negatives(positives, ["i1", "i2", "i3", "i4"], 2, seed=7)
    second = sample_negatives(positives, ["i1", "i2", "i3", "i4"], 2, seed=7)

    assert first == second
    assert all(row["object_id"] not in positives[row["user_key"]] for row in first)
    assert {row["negative_source"] for row in first} == {"SAMPLED"}


def test_future_feature_and_forbidden_aggregate_are_rejected():
    now = datetime.now(timezone.utc)

    assert find_forbidden_fields(["title", "rating", "user_click_last3m"]) == ["rating", "user_click_last3m"]
    with pytest.raises(ValueError, match="事件之后"):
        assert_no_future_features(
            [{"event_time": now, "feature_cutoff": now + timedelta(seconds=1)}]
        )


def test_real_events_use_highest_session_gain_and_only_mature_exposure_negative():
    now = datetime(2026, 9, 3, tzinfo=timezone.utc)
    events = pd.DataFrame(
        [
            _real_event("e1", "u1", "i1", "IMPRESSION", now, 1),
            _real_event("e2", "u1", "i1", "DETAIL_OPEN", now + timedelta(minutes=5), 1),
            _real_event("e3", "u1", "i1", "CART_ADD", now + timedelta(minutes=10), 1),
            _real_event("e4", "u1", "i2", "IMPRESSION", now, 2),
            _real_event("e5", "u1", "i3", "IMPRESSION", now + timedelta(minutes=80), 3),
        ]
    )

    samples = build_trade_training_samples(events)

    assert samples[["object_id", "gain", "negative_source"]].to_dict("records") == [
        {"object_id": "i2", "gain": 0, "negative_source": "REAL_EXPOSURE"},
        {"object_id": "i1", "gain": 3, "negative_source": None},
    ]


def test_user_refund_overrides_payment_but_operational_refund_does_not():
    now = datetime(2026, 9, 3, tzinfo=timezone.utc)
    events = pd.DataFrame(
        [
            _real_event("e1", "u1", "i1", "REFUNDED", now + timedelta(minutes=10), 1, "QUALITY_ISSUE"),
            _real_event("e2", "u1", "i1", "PAYMENT_SUCCEEDED", now, 1),
            _real_event("e3", "u1", "i2", "PAYMENT_SUCCEEDED", now, 2),
            _real_event("e4", "u1", "i2", "REFUNDED", now + timedelta(minutes=20), 2, "LATE_SUCCESS"),
        ]
    )

    samples = build_trade_training_samples(events).set_index("object_id")

    assert samples.loc["i1", "preference_negative"]
    assert samples.loc["i1", "negative_source"] == "REAL_REFUND"
    assert samples.loc["i2", "gain"] == 15


def test_two_tower_prioritizes_real_negative_without_sampling_a_positive():
    rows = [
        {"user_key": "u1", "object_id": "i1", "gain": 3, "preference_negative": False},
        {
            "user_key": "u1",
            "object_id": "i2",
            "gain": 0,
            "preference_negative": True,
            "negative_source": "REAL_REFUND",
        },
    ]

    _, positives, negatives = _training_tensors(
        rows,
        {"u1": 1},
        {"i1": 1, "i2": 2, "i3": 3},
        negative_count=2,
        seed=7,
    )

    assert positives.tolist() == [1]
    assert 2 in negatives[0].tolist()
    assert 1 not in negatives[0].tolist()


def test_real_and_synthetic_sources_cannot_be_mixed():
    items = pd.DataFrame({"data_source": ["REAL"]})
    interactions = pd.DataFrame({"data_source": ["REAL", "SYNTHETIC"]})

    with pytest.raises(ValueError, match="同一个且唯一"):
        _validate_data_source(items, interactions)


def test_two_tower_never_samples_an_object_published_after_the_event():
    event_time = datetime(2026, 1, 2, tzinfo=timezone.utc)
    rows = [
        {
            "user_key": "u1",
            "object_id": "i1",
            "gain": 3,
            "preference_negative": False,
            "event_time": event_time,
        }
    ]

    _, _, negatives = _training_tensors(
        rows,
        {"u1": 1},
        {"i1": 1, "i2": 2, "i3": 3},
        negative_count=1,
        seed=7,
        published_at_by_object={
            "i1": event_time - timedelta(days=2),
            "i2": event_time + timedelta(days=1),
            "i3": event_time - timedelta(days=1),
        },
    )

    assert negatives.tolist() == [[3]]


def _real_event(event_id, user, item, event_type, occurred_at, delivery_id, reason=None):
    return {
        "event_id": event_id,
        "user_key": user,
        "object_id": item,
        "event_type": event_type,
        "event_time": occurred_at,
        "ingested_at": occurred_at,
        "recommendation_delivery_id": delivery_id,
        "session_id": "session-1",
        "order_no": None,
        "refund_reason_code": reason,
        "data_source": "REAL",
    }


def test_future_refund_does_not_change_past_training_pairs_or_negative_sampling():
    now = datetime(2026, 9, 1, tzinfo=timezone.utc)
    initial = [{"user_key": "u", "object_id": "a", "gain": 3, "event_time": now}]
    future = {"user_key": "u", "object_id": "a", "gain": 0, "negative_source": "REAL_REFUND", "preference_negative": True, "event_time": now + timedelta(days=1)}
    first = _training_tensors(initial, {"u": 1}, {"a": 1, "b": 2, "c": 3}, 1, 7)
    second = _training_tensors([*initial, future], {"u": 1}, {"a": 1, "b": 2, "c": 3}, 1, 7)
    assert [value.tolist() for value in first] == [value.tolist() for value in second]


def test_as_of_does_not_see_late_ingested_refund_and_records_label_availability():
    now = datetime(2026, 9, 1, tzinfo=timezone.utc)
    paid = _real_event("p", "u", "a", "PAYMENT_SUCCEEDED", now, 1)
    refund = _real_event("r", "u", "a", "REFUNDED", now + timedelta(minutes=1), 1, "USER_RETURN")
    refund["ingested_at"] = now + timedelta(days=1)
    samples = build_trade_training_samples(pd.DataFrame([paid, refund]), as_of=now + timedelta(hours=1))
    assert samples.iloc[0]["gain"] == 15
    assert samples.iloc[0]["attribution_key"] == "delivery:1"
    mature = build_trade_training_samples(pd.DataFrame([paid, refund]), as_of=now + timedelta(days=2))
    assert mature.iloc[0]["preference_negative"]
    assert mature.iloc[0]["label_available_at"] == refund["ingested_at"]
