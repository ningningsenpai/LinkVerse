package ning.linkverse.payment.infrastructure.messaging;

import ning.linkverse.messaging.outbox.OutboxRelay;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PaymentOutboxScheduler 小批量触发 Relay，失败由 Outbox 状态保留而不阻塞回调。
 *
 * @author ning
 * @date 2026-08-24
 */
@Component
@ConditionalOnProperty(prefix = "linkverse.payment.outbox", name = "enabled", havingValue = "true")
public class PaymentOutboxScheduler {

    private final OutboxRelay relay;

    public PaymentOutboxScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${linkverse.payment.outbox.fixed-delay:1000}")
    public void relay() {
        relay.relayOnce(50);
    }
}
