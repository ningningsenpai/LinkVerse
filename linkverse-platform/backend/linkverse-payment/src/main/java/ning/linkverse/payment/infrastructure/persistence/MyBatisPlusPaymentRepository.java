package ning.linkverse.payment.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ning.linkverse.messaging.outbox.OutboxEventView;
import ning.linkverse.messaging.outbox.OutboxMessage;
import ning.linkverse.messaging.outbox.OutboxStats;
import ning.linkverse.payment.domain.CreatePaymentIntent;
import ning.linkverse.payment.domain.PaymentCallback;
import ning.linkverse.payment.domain.PaymentIntent;
import ning.linkverse.payment.domain.PaymentRepository;
import ning.linkverse.payment.infrastructure.persistence.entity.PaymentIntentEntity;
import ning.linkverse.payment.infrastructure.persistence.mapper.PaymentMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MyBatisPlusPaymentRepository 只访问 Payment Schema，禁止跨库读取 Trade 数据。
 *
 * @author ning
 * @date 2026-08-31
 */
@Repository
public class MyBatisPlusPaymentRepository implements PaymentRepository {

    private final PaymentMapper mapper;

    public MyBatisPlusPaymentRepository(PaymentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<PaymentIntent> findByOrderNo(String orderNo) {
        return findOne("order_no", orderNo);
    }

    @Override
    public Optional<PaymentIntent> findByIntentNo(String intentNo) {
        return findOne("intent_no", intentNo);
    }

    @Override
    public Optional<PaymentIntent> findOwned(String intentNo, long buyerId) {
        PaymentIntentEntity entity = mapper.selectOne(Wrappers.<PaymentIntentEntity>query()
                .eq("intent_no", intentNo)
                .eq("buyer_id", buyerId));
        return Optional.ofNullable(entity).map(PaymentIntentEntity::toDomain);
    }

    @Override
    public List<PaymentIntent> findDuePending(Instant cutoff, int limit) {
        return mapper.selectDuePending(cutoff, limit).stream()
                .map(PaymentIntentEntity::toDomain)
                .toList();
    }

    @Override
    public void insert(CreatePaymentIntent command, String intentNo, String status, Instant now) {
        mapper.insert(PaymentIntentEntity.create(command, intentNo, status, now));
    }

    @Override
    public boolean closePending(String orderNo, Instant now) {
        return mapper.closePending(orderNo, now) == 1;
    }

    @Override
    public Optional<String> findCallbackDigest(String provider, String notificationId) {
        return Optional.ofNullable(mapper.selectCallbackDigest(provider, notificationId));
    }

    @Override
    public void insertCallback(PaymentCallback callback, String payloadDigest, Instant now) {
        mapper.insertCallback(callback, payloadDigest, now);
    }

    @Override
    public boolean succeedPending(String intentNo, String providerTxnNo, Instant now) {
        return mapper.succeedPending(intentNo, providerTxnNo, now) == 1;
    }

    @Override
    public boolean markLateSuccess(String intentNo, String providerTxnNo, Instant now) {
        return mapper.markLateSuccess(intentNo, providerTxnNo, now) == 1;
    }

    @Override
    public void completeMockRefund(
            PaymentIntent intent,
            String providerTxnNo,
            String detailDigest,
            Instant now
    ) {
        mapper.insertResolvedLateSuccessException(compactUuid(), intent.intentNo(), detailDigest, now);
        mapper.insertSuccessfulRefund("refund:" + intent.intentNo(), intent, providerTxnNo, now);
        mapper.markRefunded(intent.intentNo(), now);
    }

    @Override
    public void completeRequestedRefund(PaymentIntent intent, String reasonCode, Instant now) {
        if (mapper.markRequestedRefundPending(intent.intentNo(), now) != 1) {
            throw new IllegalStateException("支付单当前状态不可退款");
        }
        mapper.insertSuccessfulRefund(
                "refund:" + intent.intentNo() + ":" + reasonCode,
                intent,
                intent.providerTxnNo(),
                now
        );
        if (mapper.markRefunded(intent.intentNo(), now) != 1) {
            throw new IllegalStateException("退款事实未能完成状态迁移");
        }
    }

    @Override
    public void insertOutbox(
            String eventId,
            String aggregateId,
            String eventType,
            String payloadJson,
            Instant now
    ) {
        mapper.insertOutbox(eventId, aggregateId, eventType, payloadJson, now);
    }

    @Override
    @Transactional
    public List<OutboxMessage> claim(Instant now, Instant lockedUntil, int limit) {
        List<OutboxMessage> messages = mapper.claim(now, limit);
        for (OutboxMessage message : messages) {
            mapper.markSending(message.id(), lockedUntil);
        }
        return messages;
    }

    @Override
    public void markPublished(long id, Instant publishedAt) {
        mapper.markPublished(id, publishedAt);
    }

    @Override
    public void markFailed(
            long id,
            int attempts,
            Instant nextAttemptAt,
            String errorDigest,
            boolean parked
    ) {
        mapper.markFailed(
                id,
                parked ? "PARKED" : "PENDING",
                attempts,
                nextAttemptAt,
                errorDigest
        );
    }

    @Override
    public Optional<OutboxEventView> findByEventId(String eventId) {
        return Optional.ofNullable(mapper.selectOutboxByEventId(eventId));
    }

    @Override
    public boolean replayParked(String eventId, Instant now) {
        return mapper.replayParked(eventId, now) == 1;
    }

    @Override
    public OutboxStats stats(Instant now) {
        return mapper.selectStats(now);
    }

    private Optional<PaymentIntent> findOne(String column, String value) {
        PaymentIntentEntity entity = mapper.selectOne(Wrappers.<PaymentIntentEntity>query().eq(column, value));
        return Optional.ofNullable(entity).map(PaymentIntentEntity::toDomain);
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
