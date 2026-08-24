package ning.linkverse.payment.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * PaymentBusinessMetrics 只接受代码内固定操作和结果标签，避免业务编号造成指标高基数。
 *
 * @author ning
 * @date 2026-08-24
 */
@Component
public class PaymentBusinessMetrics {

    private final MeterRegistry registry;

    public PaymentBusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String operation, String result) {
        Counter.builder("linkverse.business.operation")
                .tag("domain", "payment")
                .tag("operation", operation)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public void consume(String stream, String result, long elapsedNanos) {
        Counter.builder("linkverse.messaging.consume")
                .tag("domain", "payment")
                .tag("stream", stream)
                .tag("result", result)
                .register(registry)
                .increment();
        Timer.builder("linkverse.messaging.consume.duration")
                .tag("domain", "payment")
                .tag("stream", stream)
                .tag("result", result)
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }
}
