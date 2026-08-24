package ning.linkverse.payment.domain;

import ning.linkverse.messaging.outbox.OutboxStore;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

/**
 * PaymentRepository 定义支付本地事实、回调、退款和 Outbox 的最小访问契约。
 *
 * @author ning
 * @date 2026-08-24
 */
public interface PaymentRepository extends OutboxStore {

    Optional<PaymentIntent> findByOrderNo(String orderNo);

    Optional<PaymentIntent> findByIntentNo(String intentNo);

    Optional<PaymentIntent> findOwned(String intentNo, long buyerId);

    List<PaymentIntent> findDuePending(Instant cutoff, int limit);

    void insert(CreatePaymentIntent command, String intentNo, String status, Instant now);

    boolean closePending(String orderNo, Instant now);

    Optional<String> findCallbackDigest(String provider, String notificationId);

    void insertCallback(PaymentCallback callback, String payloadDigest, Instant now);

    boolean succeedPending(String intentNo, String providerTxnNo, Instant now);

    boolean markLateSuccess(String intentNo, String providerTxnNo, Instant now);

    void completeMockRefund(PaymentIntent intent, String providerTxnNo, String detailDigest, Instant now);

    void insertOutbox(String eventId, String aggregateId, String eventType, String payloadJson, Instant now);
}
