from linkverse_recommendation.training.http_benchmark import summarize


def test_successful_fallback_does_not_pass_model_gate():
    rows = [{"status": 200, "latency_ms": 10, "fallback": True, "valid_model": False, "validation_errors": []} for _ in range(100)]
    result = summarize(rows, 300)
    assert result["success_rate"] == 1
    assert result["valid_model_rate"] == 0
    assert result["fallback_rate"] == 1
    assert result["model_latency_p95_ms"] is None
    assert not result["passed"]


def test_empty_cohort_is_unavailable_and_slow_tail_fails():
    assert summarize([], 300)["success_rate"] is None
    rows = [{"status": 200, "latency_ms": 500 if index < 10 else 10,
             "fallback": False, "valid_model": True, "validation_errors": []} for index in range(100)]
    result = summarize(rows, 300)
    assert result["valid_model_rate"] == 1
    assert result["latency_ms"]["p95"] == 500
    assert not result["passed"]
