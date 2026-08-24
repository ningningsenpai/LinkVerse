package ning.linkverse.trade.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * TradeBusinessMetrics 统一记录固定标签的交易操作和消息消费结果。
 *
 * @author ning
 * @date 2026-08-24
 */
@Component
public class TradeBusinessMetrics {

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
}
