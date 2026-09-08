package ning.linkverse.trade.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * TradeBusinessMetrics 统一记录固定标签的交易、消息消费和推荐阶段指标。
 *
 * @author ning
 * @date 2026-08-24
 */
@Component
public class TradeBusinessMetrics {

    public enum RecommendationStage {
        HISTORY, REMOTE, FILTER, DELIVERY
    }

    public enum RecommendationFallback {
        TIMEOUT, CONNECTION, CIRCUIT_OPEN, CONCURRENCY_LIMIT,
        HTTP_ERROR, INVALID_RESPONSE, REMOTE_ERROR, INSUFFICIENT_CANDIDATES
    }

    private final MeterRegistry registry;

    public TradeBusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String operation, String result) {
        Counter.builder("linkverse.business.operation")
                .tag("domain", "trade")
                .tag("operation", operation)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public void consume(String stream, String result, long elapsedNanos) {
        Counter.builder("linkverse.messaging.consume")
                .tag("domain", "trade")
                .tag("stream", stream)
                .tag("result", result)
                .register(registry)
                .increment();
        Timer.builder("linkverse.messaging.consume.duration")
                .tag("domain", "trade")
                .tag("stream", stream)
                .tag("result", result)
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recommendationStage(RecommendationStage stage, long elapsedNanos) {
        Timer.builder("linkverse.recommendation.stage.duration")
                .tag("stage", stage.name())
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recommendationFallback(RecommendationFallback reason) {
        Counter.builder("linkverse.recommendation.fallback")
                .tag("reason", reason.name())
                .register(registry)
                .increment();
    }
}
