from types import SimpleNamespace

import numpy as np
import pandas as pd
import pytest

from linkverse_recommendation.pipeline.ranking.features import recall_features, relevance_scores
from linkverse_recommendation.training.ranker_iteration import group_dataset, window_queries
from linkverse_recommendation.training.evaluation import _evaluation_time
from linkverse_recommendation.data.split import TemporalSplit


def test_percentile_preserves_ties_and_is_invariant_to_score_scale():
    scores = np.asarray([-4., -4., 0., 1.])
    assert relevance_scores(scores, "percentile") == pytest.approx([1 / 6, 1 / 6, 2 / 3, 1])
    assert relevance_scores(scores * .001 + 8, "percentile") == pytest.approx(relevance_scores(scores, "percentile"))
    assert relevance_scores([0, 0, 0], "percentile") == pytest.approx([.5, .5, .5])
    with pytest.raises(ValueError, match="有限值"):
        relevance_scores([np.nan], "raw")


def test_missing_fusion_does_not_reuse_old_ranker_score():
    values = recall_features({"score": 999, "sources": ["TWO_TOWER"]}, .2, True, 4)
    assert values[:5] == pytest.approx([.2, 1, 0, 0, .25])
    assert recall_features({"recall_score": 0, "sources": []}, 0, False, 0)[3] == 1


def test_window_features_cannot_use_recall_or_labels_from_the_future():
    start, middle, end = map(pd.Timestamp, ("2026-02-06T00:00Z", "2026-02-20T00:00Z", "2026-02-26T00:00Z"))
    model = SimpleNamespace(serving_state={"fit_cutoff": (start - pd.Timedelta(seconds=1)).isoformat(),
                                          "items": {key: {"published_at": "2026-01-01T00:00Z"} for key in "abcd"}})
    def row(item, time, available=None):
        return {"user_key": "u", "object_id": item, "gain": 7, "event_time": time,
                "label_available_at": available if available is not None else time}
    rows = [row("a", start - pd.Timedelta(seconds=1)), row("a", start), row("b", start),
            row("c", start, middle), row("d", middle)]
    queries = window_queries(model, rows, start, middle)
    assert queries[0]["gains"] == {"b": 7}
    assert queries[0]["context"]["exclude_ids"] == "a"
    assert window_queries(model, rows, middle, end)[0]["gains"] == {"d": 7}
    model.serving_state["fit_cutoff"] = start.isoformat()
    with pytest.raises(ValueError, match="早于"):
        window_queries(model, rows, start, middle)


def test_training_preserves_serving_pool_without_injecting_missing_targets(tmp_path):
    features = np.arange(30, dtype=np.float32).reshape(2, 15)
    model = SimpleNamespace(candidate_features=lambda *args, **kwargs: (["a", "b"], {}, features, None))
    now = pd.Timestamp("2026-02-06T00:00Z").to_pydatetime()
    queries = [{"user_key": "u", "gains": {"a": 7}, "now": now, "context": {}},
               {"user_key": "v", "gains": {"missing": 7}, "now": now, "context": {}}]
    (x, y, groups), audit = group_dataset(model, queries, tmp_path / "groups.npz", 7)
    assert groups == [2]
    assert sorted(y.tolist()) == [0, 7]
    assert sorted(map(tuple, x)) == sorted(map(tuple, features))
    assert audit["queries"] == 2 and audit["missed_or_constant_groups"] == 1
    assert audit["positive_injection"] is False


def test_evaluation_time_rejects_future_history_and_late_queries():
    start = pd.Timestamp("2026-02-26T00:00Z")
    earlier = {"event_time": start - pd.Timedelta(hours=2), "label_available_at": start - pd.Timedelta(hours=1)}
    split = TemporalSplit((earlier,), (), (), ({"event_time": start},))
    assert _evaluation_time(split, start) == start
    with pytest.raises(ValueError, match="尚不可用"):
        _evaluation_time(split, start - pd.Timedelta(hours=1))
    with pytest.raises(ValueError, match="首个目标"):
        _evaluation_time(split, start + pd.Timedelta(seconds=1))
