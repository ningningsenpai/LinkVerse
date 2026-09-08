import json
from datetime import datetime, timedelta, timezone

import pytest
import torch

from linkverse_recommendation.data.split import fixed_temporal_split
from linkverse_recommendation.training.recording import RunRecorder, record
from linkverse_recommendation.training.trainer import _evaluate_tower
from linkverse_recommendation.training.two_tower import TwoTowerConfig, create_model


def test_evaluation_disables_dropout_and_restores_training_mode():
    torch.manual_seed(7)
    model = create_model(TwoTowerConfig(3, 40, 8, (16,), dropout=.7))
    rows = [{"user_key": "u", "object_id": "i", "gain": 3}]
    vocabulary = {"i": 1, **{str(index): index for index in range(2, 41)}}
    values = [_evaluate_tower(model, rows, {"u": 1}, vocabulary) for _ in range(3)]
    assert values[0] == values[1] == values[2]
    assert model.training
    model.eval()
    _evaluate_tower(model, rows, {"u": 1}, vocabulary)
    assert not model.training


def test_fixed_split_does_not_move_boundary_when_rows_change(tmp_path):
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)
    rows = [{"user_key": "u", "object_id": "i", "event_time": start + timedelta(hours=index)} for index in range(100)]
    path = tmp_path / "split.json"
    original = fixed_temporal_split(rows, path)
    changed = fixed_temporal_split(rows[10:], path)
    assert original.validation == changed.validation
    with pytest.raises(ValueError, match="超出"):
        fixed_temporal_split(rows + [{**rows[-1], "event_time": start + timedelta(days=10)}], path)


def test_fixed_split_rejects_cross_window_attribution(tmp_path):
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)
    rows = [{"user_key": "u", "object_id": "i", "event_time": start + timedelta(hours=index), "attribution_key": "same-delivery"} for index in range(100)]
    with pytest.raises(ValueError, match="归因链"):
        fixed_temporal_split(rows, tmp_path / "split.json")


def test_failed_run_keeps_failure_and_original_observations(tmp_path, monkeypatch):
    monkeypatch.setattr(RunRecorder, "_monitor", lambda self: None)
    destination = tmp_path / "run"
    with pytest.raises(RuntimeError):
        with RunRecorder(destination, {"operation": "test"}):
            record("epoch-metrics", train_loss=.5, validation_ndcg=None)
            raise RuntimeError("模拟训练失败")
    assert json.loads((destination / "status.json").read_text())["status"] == "FAILED"
    observation = json.loads((destination / "raw/epoch-metrics.jsonl").read_text())
    assert observation["train_loss"] == .5 and observation["validation_ndcg"] is None
    with pytest.raises(FileExistsError):
        RunRecorder(destination, {})
