package ning.linkverse.trade.infrastructure.persistence;

import ning.linkverse.messaging.outbox.OutboxMessage;
import ning.linkverse.trade.domain.seckill.SeckillCampaign;
import ning.linkverse.trade.domain.seckill.SeckillRepository;
import ning.linkverse.trade.domain.seckill.SeckillRequest;
import ning.linkverse.trade.domain.seckill.SeckillReservation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JdbcSeckillRepository 将秒杀权威状态、消费幂等和 Outbox 限定在 Trade Schema。
 *
 * @author ning
 * @date 2026-08-24
 */
@Repository
public class JdbcSeckillRepository implements SeckillRepository {

    private static final String RESERVATION_SELECT = """
            SELECT id, reservation_no, event_id, campaign_id, campaign_version,
                   user_id, idempotency_key, request_fingerprint, status, order_no,
                   failure_code, created_at, updated_at
            FROM seckill_reservation
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcSeckillRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<SeckillCampaign> findCampaign(long campaignId) {
        return findCampaigns("WHERE c.id = ?", campaignId).stream().findFirst();
    }

    @Override
    public List<SeckillCampaign> findCampaigns() {
        return findCampaigns("ORDER BY c.id");
    }

    private List<SeckillCampaign> findCampaigns(String predicate, Object... parameters) {
        return jdbcTemplate.query("""
                        SELECT c.id, c.campaign_no, c.listing_id, l.seller_id, l.title, l.author,
                               l.unit_price, l.currency, c.version, c.status, c.initial_stock,
                               c.starts_at, c.ends_at
                        FROM seckill_campaign c
                        JOIN book_listing l ON l.id = c.listing_id
                        """ + predicate, (rs, rowNumber) -> new SeckillCampaign(
                        rs.getLong("id"),
                        rs.getString("campaign_no"),
                        rs.getLong("listing_id"),
                        rs.getLong("seller_id"),
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getBigDecimal("unit_price"),
                        rs.getString("currency"),
                        rs.getLong("version"),
                        rs.getString("status"),
                        rs.getInt("initial_stock"),
                        rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("ends_at").toInstant()
                ), parameters);
    }

    @Override
    public Optional<SeckillReservation> findReservation(String reservationNo) {
        return findReservationWhere("WHERE reservation_no = ?", reservationNo);
    }

    @Override
    public Optional<SeckillReservation> findReservationForUser(String reservationNo, long userId) {
        return findReservationWhere("WHERE reservation_no = ? AND user_id = ?", reservationNo, userId);
    }

    @Override
    public Optional<SeckillReservation> findByCampaignAndUser(long campaignId, long userId) {
        return findReservationWhere("WHERE campaign_id = ? AND user_id = ?", campaignId, userId);
    }

    @Override
    public void insertPending(SeckillRequest request, String eventJson) {
        jdbcTemplate.update("""
                        INSERT INTO seckill_reservation (
                            reservation_no, event_id, campaign_id, campaign_version, user_id,
                            idempotency_key, request_fingerprint, status, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PUBLISH_PENDING', ?, ?)
                        """,
                request.reservationNo(), request.eventId(), request.campaignId(), request.campaignVersion(),
                request.userId(), request.idempotencyKey(), request.requestFingerprint(),
                Timestamp.from(request.occurredAt()), Timestamp.from(request.occurredAt())
        );
        insertOutbox(request.eventId(), request.reservationNo(), "SeckillRequested", "seckill.request", eventJson,
                request.occurredAt());
    }

    @Override
    public boolean markPublished(String eventId, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE seckill_reservation
                        SET status = 'PUBLISHED', updated_at = ?
                        WHERE event_id = ? AND status = 'PUBLISH_PENDING'
                        """, Timestamp.from(now), eventId) == 1;
    }

    @Override
    public Optional<SeckillRequest> lockRequest(String eventId) {
        return jdbcTemplate.query("""
                        SELECT reservation_no, event_id, campaign_id, campaign_version, user_id,
                               idempotency_key, request_fingerprint, created_at
                        FROM seckill_reservation
                        WHERE event_id = ?
                        FOR UPDATE
                        """, (rs, rowNumber) -> new SeckillRequest(
                        rs.getString("reservation_no"),
                        rs.getString("event_id"),
                        rs.getLong("campaign_id"),
                        rs.getLong("campaign_version"),
                        rs.getLong("user_id"),
                        rs.getString("idempotency_key"),
                        rs.getString("request_fingerprint"),
                        rs.getTimestamp("created_at").toInstant()
                ), eventId).stream().findFirst();
    }

    @Override
    public void markOrderCreated(
            long reservationId,
            String orderNo,
            Instant now,
            String resultEventId,
            String resultJson
    ) {
        jdbcTemplate.update("""
                        UPDATE seckill_reservation
                        SET status = 'ORDER_CREATED', order_no = ?, updated_at = ?
                        WHERE id = ? AND status IN ('PUBLISH_PENDING', 'PUBLISHED')
                        """, orderNo, Timestamp.from(now), reservationId);
        insertOutbox(resultEventId, Long.toString(reservationId), "SeckillReservationSucceeded",
                "seckill.result", resultJson, now);
    }

    @Override
    public void markFailed(
            long reservationId,
            String failureCode,
            Instant now,
            String resultEventId,
            String resultJson
    ) {
        jdbcTemplate.update("""
                        UPDATE seckill_reservation
                        SET status = 'FAILED', failure_code = ?, updated_at = ?
                        WHERE id = ? AND status IN ('PUBLISH_PENDING', 'PUBLISHED')
                        """, failureCode, Timestamp.from(now), reservationId);
        insertOutbox(resultEventId, Long.toString(reservationId), "SeckillReservationRejected",
                "seckill.result", resultJson, now);
    }

    @Override
    public boolean recordConsumed(String consumer, String eventId, String eventType, Instant now) {
        try {
            return jdbcTemplate.update("""
                            INSERT INTO consumed_event (consumer_name, event_id, event_type, consumed_at)
                            VALUES (?, ?, ?, ?)
                            """, consumer, eventId, eventType, Timestamp.from(now)) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public List<SeckillReservation> findPendingBefore(Instant cutoff, int limit) {
        return jdbcTemplate.query(RESERVATION_SELECT + """
                        WHERE status = 'PUBLISH_PENDING' AND updated_at <= ?
                        ORDER BY id LIMIT ?
                        """, (rs, rowNumber) -> mapReservation(rs), Timestamp.from(cutoff), limit);
    }

    @Override
    public int authoritativeAvailable(long listingId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT available FROM sku_stock WHERE listing_id = ?", Integer.class, listingId);
        return value == null ? 0 : value;
    }

    @Override
    @Transactional
    public List<OutboxMessage> claim(Instant now, Instant lockedUntil, int limit) {
        List<OutboxMessage> messages = jdbcTemplate.query("""
                        SELECT id, event_id, exchange_name, routing_key, payload_json,
                               attempt_count, created_at
                        FROM trade_outbox_event
                        WHERE (status = 'PENDING' OR (status = 'SENDING' AND locked_until <= ?))
                          AND next_attempt_at <= ?
                        ORDER BY id LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """, (rs, rowNumber) -> new OutboxMessage(
                        rs.getLong("id"), rs.getString("event_id"), rs.getString("exchange_name"),
                        rs.getString("routing_key"), rs.getString("payload_json"),
                        rs.getInt("attempt_count"), rs.getTimestamp("created_at").toInstant()
                ), Timestamp.from(now), Timestamp.from(now), limit);
        for (OutboxMessage message : messages) {
            jdbcTemplate.update("""
                            UPDATE trade_outbox_event SET status = 'SENDING', locked_until = ?
                            WHERE id = ? AND status IN ('PENDING', 'SENDING')
                            """, Timestamp.from(lockedUntil), message.id());
        }
        return messages;
    }

    @Override
    @Transactional
    public void markPublished(long id, Instant publishedAt) {
        String eventId = jdbcTemplate.query(
                "SELECT event_id FROM trade_outbox_event WHERE id = ?",
                (rs, rowNumber) -> rs.getString("event_id"), id).stream().findFirst().orElse(null);
        jdbcTemplate.update("""
                        UPDATE trade_outbox_event
                        SET status = 'PUBLISHED', published_at = ?, locked_until = NULL
                        WHERE id = ? AND status = 'SENDING'
                        """, Timestamp.from(publishedAt), id);
        if (eventId != null) {
            markPublished(eventId, publishedAt);
        }
    }

    @Override
    public void markFailed(long id, int attempts, Instant nextAttemptAt, String errorDigest, boolean parked) {
        jdbcTemplate.update("""
                        UPDATE trade_outbox_event
                        SET status = ?, attempt_count = ?, next_attempt_at = ?, locked_until = NULL,
                            last_error_digest = ?
                        WHERE id = ? AND status = 'SENDING'
                        """, parked ? "PARKED" : "PENDING", attempts, Timestamp.from(nextAttemptAt),
                errorDigest, id);
    }

    private void insertOutbox(
            String eventId,
            String aggregateId,
            String eventType,
            String routingKey,
            String eventJson,
            Instant now
    ) {
        jdbcTemplate.update("""
                        INSERT INTO trade_outbox_event (
                            event_id, aggregate_id, event_type, exchange_name, routing_key,
                            payload_json, status, attempt_count, next_attempt_at, created_at
                        ) VALUES (?, ?, ?, 'linkverse.events', ?, ?, 'PENDING', 0, ?, ?)
                        """, eventId, aggregateId, eventType, routingKey, eventJson,
                Timestamp.from(now), Timestamp.from(now));
    }

    private Optional<SeckillReservation> findReservationWhere(String predicate, Object... parameters) {
        return jdbcTemplate.query(RESERVATION_SELECT + predicate,
                (rs, rowNumber) -> mapReservation(rs), parameters).stream().findFirst();
    }

    private SeckillReservation mapReservation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SeckillReservation(
                rs.getLong("id"), rs.getString("reservation_no"), rs.getString("event_id"),
                rs.getLong("campaign_id"), rs.getLong("campaign_version"), rs.getLong("user_id"),
                rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                rs.getString("status"), rs.getString("order_no"), rs.getString("failure_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()
        );
    }
}
