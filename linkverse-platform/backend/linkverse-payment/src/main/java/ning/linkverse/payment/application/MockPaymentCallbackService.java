package ning.linkverse.payment.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.core.error.PlatformException;
import ning.linkverse.messaging.MessageEnvelope;
import ning.linkverse.payment.domain.PaymentCallback;
import ning.linkverse.payment.domain.PaymentErrorCode;
import ning.linkverse.payment.domain.PaymentIntent;
import ning.linkverse.payment.domain.PaymentRepository;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * MockPaymentCallbackService 在验签后幂等收敛支付成功或迟到退款。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
@Profile({"local", "test"})
public class MockPaymentCallbackService {

    private final PaymentRepository repository;
    private final MockHmacVerifier verifier;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MockPaymentCallbackService(
            PaymentRepository repository,
            MockHmacVerifier verifier,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.verifier = verifier;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public PaymentIntent accept(String timestamp, String signature, byte[] rawBody) {
        verifier.verify(timestamp, signature, rawBody);
        PaymentCallback callback = read(rawBody);
        String digest = digest(rawBody);
        String existingDigest = repository.findCallbackDigest("MOCK", callback.notificationId()).orElse(null);
        if (existingDigest != null) {
            if (!existingDigest.equals(digest)) {
                throw new PlatformException(PaymentErrorCode.CALLBACK_REUSED);
            }
            return requireIntent(callback.intentNo());
        }

        PaymentIntent intent = requireIntent(callback.intentNo());
        validate(callback, intent);
        Instant now = clock.instant();
        try {
            repository.insertCallback(callback, digest, now);
        } catch (DuplicateKeyException exception) {
            PaymentIntent replay = requireIntent(callback.intentNo());
            if (callback.providerTxnNo().equals(replay.providerTxnNo())) {
                return replay;
            }
            throw new PlatformException(PaymentErrorCode.CALLBACK_REUSED);
        }

        if (repository.succeedPending(intent.intentNo(), callback.providerTxnNo(), now)) {
            PaymentIntent succeeded = requireIntent(intent.intentNo());
            writeSucceededEvent(succeeded, now);
            return succeeded;
        }
        if (repository.markLateSuccess(intent.intentNo(), callback.providerTxnNo(), now)) {
            PaymentIntent refundPending = requireIntent(intent.intentNo());
            repository.completeMockRefund(refundPending, callback.providerTxnNo(), digest, now);
            return requireIntent(intent.intentNo());
        }
        PaymentIntent current = requireIntent(intent.intentNo());
        if (("SUCCEEDED".equals(current.status()) || "REFUNDED".equals(current.status()))
                && callback.providerTxnNo().equals(current.providerTxnNo())) {
            return current;
        }
        throw new PlatformException(PaymentErrorCode.INTENT_NOT_PAYABLE);
    }

    private void validate(PaymentCallback callback, PaymentIntent intent) {
        if (!"SUCCEEDED".equals(callback.status())
                || !intent.orderNo().equals(callback.orderNo())
                || intent.merchantId() != callback.merchantId()
                || callback.amount() == null
                || intent.amount().compareTo(callback.amount()) != 0
                || !intent.currency().equals(callback.currency())
                || callback.notificationId() == null
                || callback.providerTxnNo() == null) {
            throw new PlatformException(PaymentErrorCode.CALLBACK_MISMATCH);
        }
    }

    private PaymentCallback read(byte[] rawBody) {
        try {
            return objectMapper.readValue(rawBody, PaymentCallback.class);
        } catch (Exception exception) {
            throw new PlatformException(PaymentErrorCode.CALLBACK_MISMATCH);
        }
    }

    private PaymentIntent requireIntent(String intentNo) {
        return repository.findByIntentNo(intentNo)
                .orElseThrow(() -> new PlatformException(PaymentErrorCode.INTENT_NOT_FOUND));
    }

    private void writeSucceededEvent(PaymentIntent intent, Instant now) {
        try {
            String eventId = compactUuid();
            MessageEnvelope<Map<String, Object>> envelope = new MessageEnvelope<>(
                    eventId,
                    "PaymentSucceeded",
                    1,
                    intent.orderNo(),
                    now,
                    traceId(),
                    Map.of(
                            "intent_no", intent.intentNo(),
                            "order_no", intent.orderNo(),
                            "amount", intent.amount().toPlainString(),
                            "currency", intent.currency(),
                            "provider_txn_no", intent.providerTxnNo()
                    )
            );
            repository.insertOutbox(
                    eventId,
                    intent.orderNo(),
                    "PaymentSucceeded",
                    objectMapper.writeValueAsString(envelope),
                    now
            );
        } catch (Exception exception) {
            throw new IllegalStateException("序列化支付成功事件失败", exception);
        }
    }

    private String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("运行环境不支持SHA-256", exception);
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
