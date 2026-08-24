package ning.linkverse.payment.infrastructure.persistence;

import ning.linkverse.messaging.outbox.OutboxMessage;
import ning.linkverse.payment.domain.CreatePaymentIntent;
import ning.linkverse.payment.domain.PaymentCallback;
import ning.linkverse.payment.domain.PaymentIntent;
import ning.linkverse.payment.domain.PaymentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JdbcPaymentRepository 只访问 Payment Schema，禁止跨库读取 Trade 数据。
 *
 * @author ning
 * @date 2026-08-24
 */
@Repository
public class JdbcPaymentRepository implements PaymentRepository {

    private static final String INTENT_SELECT = """
            SELECT id, intent_no, order_no, buyer_id, merchant_id, amount, currency,
                   provider, status, provider_txn_no, expire_at, succeeded_at,
                   closed_at, refunded_at, created_at
            FROM payment_intent
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcPaymentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PaymentIntent> findByOrderNo(String orderNo) {
        return find(INTENT_SELECT + " WHERE order_no = ?", orderNo);
    }

    @Override
    public Optional<PaymentIntent> findByIntentNo(String intentNo) {
        return find(INTENT_SELECT + " WHERE intent_no = ?", intentNo);
    }

    @Override
    public Optional<PaymentIntent> findOwned(String intentNo, long buyerId) {
        return find(INTENT_SELECT + " WHERE intent_no = ? AND buyer_id = ?", intentNo, buyerId);
    }

    @Override
    public List<PaymentIntent> findDuePending(Instant cutoff, int limit) {
        return jdbcTemplate.query(
                INTENT_SELECT + " WHERE status = 'PENDING' AND expire_at <= ? ORDER BY expire_at, id LIMIT ?",
                (resultSet, rowNumber) -> mapIntent(resultSet),
                Timestamp.from(cutoff),
                limit
        );
    }

    @Override
    public void insert(CreatePaymentIntent command, String intentNo, String status, Instant now) {
        Instant closedAt = "CLOSED".equals(status) ? now : null;
        jdbcTemplate.update("""
                        INSERT INTO payment_intent (
                            intent_no, order_no, buyer_id, merchant_id, amount, currency,
                            provider, status, expire_at, closed_at, version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, 'MOCK', ?, ?, ?, 0, ?, ?)
                        """,
                intentNo,
                command.orderNo(),
                command.buyerId(),
                command.merchantId(),
                command.amount(),
                command.currency(),
                status,
                Timestamp.from(command.expireAt()),
                timestamp(closedAt),
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    @Override
    public boolean closePending(String orderNo, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE payment_intent
                        SET status = 'CLOSED', closed_at = ?, version = version + 1, updated_at = ?
                        WHERE order_no = ? AND status = 'PENDING'
                        """, Timestamp.from(now), Timestamp.from(now), orderNo) == 1;
    }

    @Override
    public Optional<String> findCallbackDigest(String provider, String notificationId) {
        return jdbcTemplate.query(
                "SELECT payload_sha256 FROM payment_callback_log WHERE provider = ? AND notification_id = ?",
                (resultSet, rowNumber) -> resultSet.getString("payload_sha256"),
                provider,
                notificationId
        ).stream().findFirst();
    }

    @Override
    public void insertCallback(PaymentCallback callback, String payloadDigest, Instant now) {
        jdbcTemplate.update("""
                        INSERT INTO payment_callback_log (
                            provider, notification_id, provider_txn_no, intent_no,
                            payload_sha256, received_at
                        ) VALUES ('MOCK', ?, ?, ?, ?, ?)
                        """,
                callback.notificationId(),
                callback.providerTxnNo(),
                callback.intentNo(),
                payloadDigest,
                Timestamp.from(now)
        );
    }

    @Override
    public boolean succeedPending(String intentNo, String providerTxnNo, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE payment_intent
                        SET status = 'SUCCEEDED', provider_txn_no = ?, succeeded_at = ?,
                            version = version + 1, updated_at = ?
                        WHERE intent_no = ? AND status = 'PENDING'
                        """, providerTxnNo, Timestamp.from(now), Timestamp.from(now), intentNo) == 1;
    }

    @Override
    public boolean markLateSuccess(String intentNo, String providerTxnNo, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE payment_intent
                        SET status = 'REFUND_PENDING', provider_txn_no = ?, succeeded_at = ?,
                            version = version + 1, updated_at = ?
                        WHERE intent_no = ? AND status = 'CLOSED'
                        """, providerTxnNo, Timestamp.from(now), Timestamp.from(now), intentNo) == 1;
    }

    @Override
    public void completeMockRefund(
            PaymentIntent intent,
            String providerTxnNo,
            String detailDigest,
            Instant now
    ) {
        String exceptionNo = compactUuid();
        String refundRequestNo = "refund:" + intent.intentNo();
        jdbcTemplate.update("""
                        INSERT INTO payment_exception (
                            exception_no, intent_no, exception_type, status,
                            detail_digest, created_at, resolved_at
                        ) VALUES (?, ?, 'LATE_SUCCESS', 'RESOLVED', ?, ?, ?)
                        """, exceptionNo, intent.intentNo(), detailDigest, Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                        INSERT INTO refund_attempt (
                            refund_request_no, intent_no, provider_txn_no, amount, currency,
                            status, attempt_count, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', 1, ?, ?)
                        ON DUPLICATE KEY UPDATE refund_request_no = refund_attempt.refund_request_no
                        """,
                refundRequestNo,
                intent.intentNo(),
                providerTxnNo,
                intent.amount(),
                intent.currency(),
                Timestamp.from(now),
                Timestamp.from(now)
        );
        jdbcTemplate.update("""
                        UPDATE payment_intent
                        SET status = 'REFUNDED', refunded_at = ?, version = version + 1, updated_at = ?
                        WHERE intent_no = ? AND status = 'REFUND_PENDING'
                        """, Timestamp.from(now), Timestamp.from(now), intent.intentNo());
    }

    @Override
    public void insertOutbox(
            String eventId,
            String aggregateId,
            String eventType,
            String payloadJson,
            Instant now
    ) {
        jdbcTemplate.update("""
                        INSERT INTO outbox_event (
                            event_id, aggregate_id, event_type, exchange_name, routing_key,
                            payload_json, status, attempt_count, next_attempt_at, created_at
                        ) VALUES (?, ?, ?, 'linkverse.events', 'payment.fact', ?, 'PENDING', 0, ?, ?)
                        """,
                eventId,
                aggregateId,
                eventType,
                payloadJson,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    @Override
    @Transactional
    public List<OutboxMessage> claim(Instant now, Instant lockedUntil, int limit) {
        List<OutboxMessage> messages = jdbcTemplate.query("""
                        SELECT id, event_id, exchange_name, routing_key, payload_json,
                               attempt_count, created_at
                        FROM outbox_event
                        WHERE (status = 'PENDING' OR (status = 'SENDING' AND locked_until <= ?))
                          AND next_attempt_at <= ?
                        ORDER BY id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """, (resultSet, rowNumber) -> new OutboxMessage(
                        resultSet.getLong("id"),
                        resultSet.getString("event_id"),
                        resultSet.getString("exchange_name"),
                        resultSet.getString("routing_key"),
                        resultSet.getString("payload_json"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getTimestamp("created_at").toInstant()
                ), Timestamp.from(now), Timestamp.from(now), limit);
        for (OutboxMessage message : messages) {
            jdbcTemplate.update("""
                            UPDATE outbox_event
                            SET status = 'SENDING', locked_until = ?
                            WHERE id = ? AND status IN ('PENDING', 'SENDING')
                            """, Timestamp.from(lockedUntil), message.id());
        }
        return messages;
    }

    @Override
    public void markPublished(long id, Instant publishedAt) {
        jdbcTemplate.update("""
                        UPDATE outbox_event
                        SET status = 'PUBLISHED', published_at = ?, locked_until = NULL
                        WHERE id = ? AND status = 'SENDING'
                        """, Timestamp.from(publishedAt), id);
    }

    @Override
    public void markFailed(
            long id,
            int attempts,
            Instant nextAttemptAt,
            String errorDigest,
            boolean parked
    ) {
        jdbcTemplate.update("""
                        UPDATE outbox_event
                        SET status = ?, attempt_count = ?, next_attempt_at = ?,
                            locked_until = NULL, last_error_digest = ?
                        WHERE id = ? AND status = 'SENDING'
                        """,
                parked ? "PARKED" : "PENDING",
                attempts,
                Timestamp.from(nextAttemptAt),
                errorDigest,
                id
        );
    }

    private Optional<PaymentIntent> find(String sql, Object... parameters) {
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> mapIntent(resultSet), parameters)
                .stream().findFirst();
    }

    private PaymentIntent mapIntent(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new PaymentIntent(
                resultSet.getLong("id"),
                resultSet.getString("intent_no"),
                resultSet.getString("order_no"),
                resultSet.getLong("buyer_id"),
                resultSet.getLong("merchant_id"),
                resultSet.getBigDecimal("amount"),
                resultSet.getString("currency"),
                resultSet.getString("provider"),
                resultSet.getString("status"),
                resultSet.getString("provider_txn_no"),
                instant(resultSet.getTimestamp("expire_at")),
                instant(resultSet.getTimestamp("succeeded_at")),
                instant(resultSet.getTimestamp("closed_at")),
                instant(resultSet.getTimestamp("refunded_at")),
                instant(resultSet.getTimestamp("created_at"))
        );
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
