"""不依赖业务标识的 Prometheus 文本指标。"""

from __future__ import annotations

import threading
from collections import Counter


LATENCY_BUCKETS = (0.01, 0.03, 0.1, 0.3, 0.8, 2.0)


class RecommendationMetrics:
    """仅按固定领域和结果枚举统计请求，禁止用户、商品和请求 ID 标签。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._requests: Counter[tuple[str, str]] = Counter()
        self._latency_buckets: Counter[tuple[str, float]] = Counter()
        self._latency_sum: Counter[str] = Counter()
        self._model_load_failures = 0

    def observe_request(self, domain: str, outcome: str, elapsed_seconds: float) -> None:
        safe_domain = domain if domain == "trade" else "unknown"
        safe_outcome = outcome if outcome in {"success", "invalid", "unavailable"} else "unknown"
        with self._lock:
            self._requests[(safe_domain, safe_outcome)] += 1
            self._latency_sum[safe_domain] += elapsed_seconds
            for bucket in LATENCY_BUCKETS:
                if elapsed_seconds <= bucket:
                    self._latency_buckets[(safe_domain, bucket)] += 1

    def record_model_load_failure(self) -> None:
        with self._lock:
            self._model_load_failures += 1

    def render(self) -> str:
        with self._lock:
            lines = [
                "# HELP linkverse_recommendation_requests_total 推荐请求总数。",
                "# TYPE linkverse_recommendation_requests_total counter",
            ]
            for (domain, outcome), value in sorted(self._requests.items()):
                lines.append(
                    f'linkverse_recommendation_requests_total{{domain="{domain}",outcome="{outcome}"}} {value}'
                )
            lines.extend(
                [
                    "# HELP linkverse_recommendation_latency_seconds 推荐推断耗时。",
                    "# TYPE linkverse_recommendation_latency_seconds histogram",
                ]
            )
            for domain in sorted(self._latency_sum):
                for bucket in LATENCY_BUCKETS:
                    value = self._latency_buckets[(domain, bucket)]
                    lines.append(
                        f'linkverse_recommendation_latency_seconds_bucket{{domain="{domain}",le="{bucket}"}} {value}'
                    )
                total = sum(
                    value for (item_domain, _), value in self._requests.items() if item_domain == domain
                )
                lines.append(
                    f'linkverse_recommendation_latency_seconds_bucket{{domain="{domain}",le="+Inf"}} {total}'
                )
                lines.append(
                    f'linkverse_recommendation_latency_seconds_sum{{domain="{domain}"}} {self._latency_sum[domain]}'
                )
                lines.append(f'linkverse_recommendation_latency_seconds_count{{domain="{domain}"}} {total}')
            lines.extend(
                [
                    "# HELP linkverse_recommendation_model_load_failures_total 模型加载失败总数。",
                    "# TYPE linkverse_recommendation_model_load_failures_total counter",
                    f"linkverse_recommendation_model_load_failures_total {self._model_load_failures}",
                ]
            )
            return "\n".join(lines) + "\n"
