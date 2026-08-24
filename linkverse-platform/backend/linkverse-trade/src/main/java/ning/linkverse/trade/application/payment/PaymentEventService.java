package ning.linkverse.trade.application.payment;

import ning.linkverse.trade.domain.TradeRepository;
import ning.linkverse.trade.domain.order.PayableOrder;
import ning.linkverse.trade.application.TradeBusinessMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * PaymentEventService 在一个 Trade 事务内完成消费去重、快照校验和状态迁移。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class PaymentEventService {

    private static final String CONSUMER = "trade-payment-fact-v1";

    private final TradeRepository repository;
    private final TradeBusinessMetrics metrics;

    @Autowired
    public PaymentEventService(TradeRepository repository, TradeBusinessMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    public PaymentEventService(TradeRepository repository) {
        this(repository, null);
    }

    @Transactional
    public void acceptSucceeded(
            String eventId,
            String intentNo,
            String orderNo,
            BigDecimal amount,
            String currency,
            Instant occurredAt
    ) {
        if (!repository.recordConsumedEvent(CONSUMER, eventId, "PaymentSucceeded", occurredAt)) {
            duplicate("payment_succeeded");
            return;
        }
        PayableOrder order = repository.findPayableByOrder(orderNo)
                .orElseThrow(() -> new IllegalStateException("支付事件对应订单不存在"));
        if (order.amount().compareTo(amount) != 0 || !order.currency().equals(currency)) {
            throw new IllegalStateException("支付事件金额或币种与订单快照不一致");
        }
        if ("PAID".equals(order.status()) || "CLOSED".equals(order.status())) {
            return;
        }
        if (!repository.markPaid(orderNo, intentNo, occurredAt)) {
            throw new IllegalStateException("支付成功事件未能完成订单状态迁移");
        }
    }

    @Transactional
    public boolean recordClosed(String eventId, String occurredAt) {
        boolean recorded = repository.recordConsumedEvent(
                CONSUMER,
                eventId,
                "PaymentClosed",
                Instant.parse(occurredAt)
        );
        if (!recorded) {
            duplicate("payment_closed");
        }
        return recorded;
    }

    private void duplicate(String stream) {
        if (metrics != null) {
            metrics.record("consumer_duplicate", stream);
        }
    }
}
