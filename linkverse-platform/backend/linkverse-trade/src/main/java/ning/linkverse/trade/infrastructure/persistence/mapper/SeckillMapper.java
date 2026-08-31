package ning.linkverse.trade.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ning.linkverse.messaging.outbox.OutboxEventView;
import ning.linkverse.messaging.outbox.OutboxMessage;
import ning.linkverse.messaging.outbox.OutboxStats;
import ning.linkverse.trade.domain.seckill.SeckillCampaign;
import ning.linkverse.trade.domain.seckill.SeckillRequest;
import ning.linkverse.trade.domain.seckill.SeckillReservation;
import ning.linkverse.trade.infrastructure.persistence.entity.SeckillReservationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * SeckillMapper 提供秒杀预约、消费幂等和 Trade Outbox 的 MyBatis-Plus 数据访问入口。
 *
 * @author ning
 * @date 2026-08-31
 */
@Mapper
public interface SeckillMapper extends BaseMapper<SeckillReservationEntity> {

    String RESERVATION_SELECT = """
            SELECT id AS id, reservation_no AS reservationNo, event_id AS eventId,
                   campaign_id AS campaignId, campaign_version AS campaignVersion,
                   user_id AS userId, idempotency_key AS idempotencyKey,
                   request_fingerprint AS requestFingerprint, status AS status,
                   order_no AS orderNo, failure_code AS failureCode,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM seckill_reservation
            """;

    String CAMPAIGN_SELECT = """
            SELECT c.id AS id, c.campaign_no AS campaignNo, c.listing_id AS listingId,
                   l.seller_id AS sellerId, l.title AS title, l.author AS author,
                   l.unit_price AS price, l.currency AS currency, c.version AS version,
                   c.status AS status, c.initial_stock AS initialStock,
                   c.starts_at AS startsAt, c.ends_at AS endsAt
            FROM seckill_campaign c
            JOIN book_listing l ON l.id = c.listing_id
            """;

    @Select(CAMPAIGN_SELECT + " WHERE c.id = #{campaignId}")
    SeckillCampaign selectCampaign(long campaignId);

    @Select(CAMPAIGN_SELECT + " ORDER BY c.id")
    List<SeckillCampaign> selectCampaigns();

    @Select(RESERVATION_SELECT + " WHERE reservation_no = #{reservationNo}")
    SeckillReservation selectReservation(String reservationNo);

    @Select(RESERVATION_SELECT + " WHERE reservation_no = #{reservationNo} AND user_id = #{userId}")
    SeckillReservation selectReservationForUser(
            @Param("reservationNo") String reservationNo,
            @Param("userId") long userId
    );

    @Select(RESERVATION_SELECT + " WHERE campaign_id = #{campaignId} AND user_id = #{userId}")
    SeckillReservation selectByCampaignAndUser(
            @Param("campaignId") long campaignId,
            @Param("userId") long userId
    );

    @Update("""
            UPDATE seckill_reservation
            SET status = 'PUBLISHED', updated_at = #{now}
            WHERE event_id = #{eventId} AND status = 'PUBLISH_PENDING'
            """)
    int markReservationPublished(@Param("eventId") String eventId, @Param("now") Instant now);

    @Select("""
            SELECT reservation_no AS reservationNo, event_id AS eventId,
                   campaign_id AS campaignId, campaign_version AS campaignVersion,
                   user_id AS userId, idempotency_key AS idempotencyKey,
                   request_fingerprint AS requestFingerprint, created_at AS occurredAt
            FROM seckill_reservation
            WHERE event_id = #{eventId}
            FOR UPDATE
            """)
    SeckillRequest lockRequest(String eventId);

    @Update("""
            UPDATE seckill_reservation
            SET status = 'ORDER_CREATED', order_no = #{orderNo}, updated_at = #{now}
            WHERE id = #{reservationId} AND status IN ('PUBLISH_PENDING', 'PUBLISHED')
            """)
    int markOrderCreated(
            @Param("reservationId") long reservationId,
            @Param("orderNo") String orderNo,
            @Param("now") Instant now
    );

    @Update("""
            UPDATE seckill_reservation
            SET status = 'FAILED', failure_code = #{failureCode}, updated_at = #{now}
            WHERE id = #{reservationId} AND status IN ('PUBLISH_PENDING', 'PUBLISHED')
            """)
    int markFailed(
            @Param("reservationId") long reservationId,
            @Param("failureCode") String failureCode,
            @Param("now") Instant now
    );

    @Insert("""
            INSERT INTO consumed_event (consumer_name, event_id, event_type, consumed_at)
            VALUES (#{consumer}, #{eventId}, #{eventType}, #{now})
            """)
    int insertConsumed(
            @Param("consumer") String consumer,
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("now") Instant now
    );

    @Select(RESERVATION_SELECT + """
             WHERE status = 'PUBLISH_PENDING' AND updated_at <= #{cutoff}
             ORDER BY id LIMIT #{limit}
            """)
    List<SeckillReservation> selectPendingBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    @Select("SELECT available FROM sku_stock WHERE listing_id = #{listingId}")
    Integer selectAuthoritativeAvailable(long listingId);

    @Insert("""
            INSERT INTO trade_outbox_event (
                event_id, aggregate_id, event_type, exchange_name, routing_key,
                payload_json, status, attempt_count, next_attempt_at, created_at
            ) VALUES (
                #{eventId}, #{aggregateId}, #{eventType}, 'linkverse.events', #{routingKey},
                #{eventJson}, 'PENDING', 0, #{now}, #{now}
            )
            """)
    int insertOutbox(
            @Param("eventId") String eventId,
            @Param("aggregateId") String aggregateId,
            @Param("eventType") String eventType,
            @Param("routingKey") String routingKey,
            @Param("eventJson") String eventJson,
            @Param("now") Instant now
    );

    @Select("""
            SELECT id AS id, event_id AS eventId, exchange_name AS exchange,
                   routing_key AS routingKey, payload_json AS payloadJson,
                   attempt_count AS attemptCount, created_at AS createdAt
            FROM trade_outbox_event
            WHERE (status = 'PENDING' OR (status = 'SENDING' AND locked_until <= #{now}))
              AND next_attempt_at <= #{now}
            ORDER BY id LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxMessage> claim(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE trade_outbox_event
            SET status = 'SENDING', locked_until = #{lockedUntil}
            WHERE id = #{id} AND status IN ('PENDING', 'SENDING')
            """)
    int markSending(@Param("id") long id, @Param("lockedUntil") Instant lockedUntil);

    @Select("SELECT event_id FROM trade_outbox_event WHERE id = #{id}")
    String selectOutboxEventId(long id);

    @Update("""
            UPDATE trade_outbox_event
            SET status = 'PUBLISHED', published_at = #{publishedAt}, locked_until = NULL
            WHERE id = #{id} AND status = 'SENDING'
            """)
    int markOutboxPublished(@Param("id") long id, @Param("publishedAt") Instant publishedAt);

    @Update("""
            UPDATE trade_outbox_event
            SET status = #{status}, attempt_count = #{attempts},
                next_attempt_at = #{nextAttemptAt}, locked_until = NULL,
                last_error_digest = #{errorDigest}
            WHERE id = #{id} AND status = 'SENDING'
            """)
    int markOutboxFailed(
            @Param("id") long id,
            @Param("status") String status,
            @Param("attempts") int attempts,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("errorDigest") String errorDigest
    );

    @Select("""
            SELECT event_id AS eventId, aggregate_id AS aggregateId, event_type AS eventType,
                   status AS status, attempt_count AS attemptCount,
                   next_attempt_at AS nextAttemptAt, created_at AS createdAt,
                   published_at AS publishedAt, last_error_digest AS lastErrorDigest
            FROM trade_outbox_event WHERE event_id = #{eventId}
            """)
    OutboxEventView selectOutboxByEventId(String eventId);

    @Update("""
            UPDATE trade_outbox_event
            SET status = 'PENDING', attempt_count = 0, next_attempt_at = #{now},
                locked_until = NULL, last_error_digest = NULL
            WHERE event_id = #{eventId} AND status = 'PARKED'
            """)
    int replayParked(@Param("eventId") String eventId, @Param("now") Instant now);

    @Select("""
            SELECT
              COALESCE(SUM(status IN ('PENDING', 'SENDING')), 0) AS pending,
              COALESCE(SUM(status = 'PARKED'), 0) AS parked,
              COALESCE(TIMESTAMPDIFF(SECOND,
                MIN(CASE WHEN status IN ('PENDING', 'SENDING') THEN created_at END), #{now}), 0)
                AS oldestPendingSeconds
            FROM trade_outbox_event
            """)
    OutboxStats selectStats(Instant now);
}
