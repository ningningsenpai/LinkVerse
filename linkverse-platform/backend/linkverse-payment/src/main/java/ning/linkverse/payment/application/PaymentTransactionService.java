package ning.linkverse.payment.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.messaging.MessageEnvelope;
import ning.linkverse.payment.domain.CreatePaymentIntent;
import ning.linkverse.payment.domain.PaymentIntent;
import ning.linkverse.payment.domain.PaymentRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * PaymentTransactionService 将单次 Intent 写入及其 Outbox 事件限定在本地事务。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class PaymentTransactionService {

    private final PaymentRepository repository;
    private final ObjectMapper objectMapper;

    public PaymentTransactionService(PaymentRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void insertPending(CreatePaymentIntent command, String intentNo, Instant now) {
        repository.insert(command, intentNo, "PENDING", now);
    }

    @Transactional
    public PaymentIntent insertClosed(CreatePaymentIntent command, String intentNo, Instant now) {
        repository.insert(command, intentNo, "CLOSED", now);
        PaymentIntent closed = repository.findByOrderNo(command.orderNo()).orElseThrow();
        writeClosedEvent(closed, now);
        return closed;
    }

    @Transactional
    public boolean closePending(PaymentIntent intent, Instant now) {
        if (!repository.closePending(intent.orderNo(), now)) {
            return false;
        }
        writeClosedEvent(repository.findByOrderNo(intent.orderNo()).orElseThrow(), now);
        return true;
    }

    private void writeClosedEvent(PaymentIntent intent, Instant now) {
        try {
            String eventId = compactUuid();
            MessageEnvelope<Map<String, Object>> envelope = new MessageEnvelope<>(
                    eventId,
                    "PaymentClosed",
                    1,
                    intent.orderNo(),
                    now,
                    traceId(),
                    Map.of(
                            "intent_no", intent.intentNo(),
                            "order_no", intent.orderNo(),
                            "amount", intent.amount().toPlainString(),
                            "currency", intent.currency(),
                            "status", intent.status()
                    )
            );
            repository.insertOutbox(
                    eventId,
                    intent.orderNo(),
                    "PaymentClosed",
                    objectMapper.writeValueAsString(envelope),
                    now
            );
        } catch (Exception exception) {
            throw new IllegalStateException("序列化支付关闭事件失败", exception);
        }
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? "trace-unavailable" : traceId;
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
