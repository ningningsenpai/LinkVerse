"""对不可变模型包执行并发 CPU 推断基准。"""

from __future__ import annotations

import argparse
import json
import math
import threading
import time
from contextvars import copy_context
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path

from linkverse_recommendation.serving.registry import LoadedTradeModel
from linkverse_recommendation.training.recording import record


def benchmark_loaded_model(
    bundle: Path,
    concurrency: int = 20,
    calls: int = 400,
    candidate_count: int = 300,
) -> dict[str, object]:
    """使用真实 Faiss 索引和混合候选执行固定并发基准，不记录用户键。"""

    if concurrency < 1 or calls < concurrency:
        raise ValueError("调用次数必须大于或等于并发数，且并发数至少为 1")
    if candidate_count < 1:
        raise ValueError("候选数量必须至少为 1")

    model = LoadedTradeModel(bundle)
    user_keys = list(model.user_vocabulary)
    if len(user_keys) < concurrency:
        raise ValueError("模型中的已知用户数量不足以执行指定并发基准")

    for user_key in user_keys[:concurrency]:
        model.recommend(user_key, candidate_count)

    first_wave = threading.Barrier(concurrency)

    def invoke(index: int) -> tuple[int, float, int]:
        if index < concurrency:
            first_wave.wait(timeout=10)
        started = time.perf_counter()
        candidates = model.recommend(user_keys[index % len(user_keys)], candidate_count)
        if not candidates:
            raise RuntimeError("推荐结果为空")
        elapsed = (time.perf_counter() - started) * 1_000
        return index, elapsed, len(candidates)

    latencies: list[float] = []
    failures = 0
    observations = []
    batch_started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(copy_context().run, invoke, index) for index in range(calls)]
        for future in as_completed(futures):
            try:
                observation = future.result()
                observations.append(observation)
                latencies.append(observation[1])
            except Exception:
                failures += 1
    batch_elapsed = time.perf_counter() - batch_started
    # 批次结束后写原始结果，避免逐请求磁盘锁成为推断工作线程的隐藏思考时间。
    for index, elapsed, returned in observations:
        record("inprocess-requests", request_index=index, latency_ms=elapsed, returned=returned, model_version=model.model_version)

    success_count = len(latencies)
    success_rate = success_count / calls
    latency = {
        "p50": _percentile(latencies, 0.50),
        "p95": _percentile(latencies, 0.95),
        "p99": _percentile(latencies, 0.99),
        "max": max(latencies, default=math.inf),
    }
    threshold_passed = success_rate >= 0.99 and latency["p95"] <= 300
    return {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "scope": "PYTHON_LOADED_MODEL_CPU_IN_PROCESS",
        "http_executed": False,
        "model_version": model.model_version,
        "concurrency": concurrency,
        "calls": calls,
        "candidate_count": candidate_count,
        "batch_elapsed_seconds": batch_elapsed,
        "throughput_rps": success_count / batch_elapsed,
        "mean_measured_concurrency": sum(latencies) / 1000 / batch_elapsed,
        "success_count": success_count,
        "failure_count": failures,
        "success_rate": success_rate,
        "latency_ms": latency,
        "thresholds": {"success_rate_min": 0.99, "p95_ms_max": 300},
        "threshold_passed": threshold_passed,
        "limitations": "该结果只覆盖 Python 进程内 CPU 推断，不代表 Trade 或 Gateway HTTP 延迟。",
    }


def _percentile(values: list[float], quantile: float) -> float:
    if not values:
        return math.inf
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * quantile) - 1)
    return ordered[index]


def main() -> None:
    parser = argparse.ArgumentParser(description="执行 Recommendation 模型并发 CPU 推断基准")
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--concurrency", type=int, default=20)
    parser.add_argument("--calls", type=int, default=400)
    parser.add_argument("--candidate-count", type=int, default=300)
    arguments = parser.parse_args()

    result = benchmark_loaded_model(
        arguments.bundle,
        arguments.concurrency,
        arguments.calls,
        arguments.candidate_count,
    )
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
