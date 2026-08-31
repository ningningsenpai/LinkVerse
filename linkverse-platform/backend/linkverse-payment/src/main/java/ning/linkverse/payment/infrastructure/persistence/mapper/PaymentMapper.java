package ning.linkverse.payment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ning.linkverse.messaging.outbox.OutboxEventView;
import ning.linkverse.messaging.outbox.OutboxMessage;
import ning.linkverse.messaging.outbox.OutboxStats;
import ning.linkverse.payment.domain.PaymentCallback;
import ning.linkverse.payment.domain.PaymentIntent;
import ning.linkverse.payment.infrastructure.persistence.entity.PaymentIntentEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * PaymentMapper 提供支付事实、回调、退款和 Outbox 的 MyBatis-Plus 数据访问入口。
 *
 * @author ning
 * @date 2026-08-31
 */
@Mapper
public interface PaymentMapper extends BaseMapper<PaymentIntentEntity> {

    @Select("""
            SELECT id, intent_no, order_no, buyer_id, merchant_id, amount, currency,
                   provider, status, provider_txn_no, expire_at, succeeded_at,
                   closed_at, refunded_at, version, created_at, updated_at
            FROM payment_intent
            WHERE status = 'PENDING' AND expire_at <= #{cutoff}
            ORDER BY expire_at, id LIMIT #{limit}
            """)
    List<PaymentIntentEntity> selectDuePending(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    @Update("""
            UPDATE payment_intent
            SET status = 'CLOSED', closed_at = #{now}, version = version + 1, updated_at = #{now}
            WHERE order_no = #{orderNo} AND status = 'PENDING'
            """)
    int closePending(@Param("orderNo") String orderNo, @Param("now") Instant now);

    @Select("""
            SELECT payload_sha256
            FROM payment_callback_log
            WHERE provider = #{provider} AND notification_id = #{notificationId}
            """)
    String selectCallbackDigest(
            @Param("provider") String provider,
            @Param("notificationId") String notificationId
    );

    @Insert("""
            INSERT INTO payment_callback_log (
                provider, notification_id, provider_txn_no, intent_no,
                payload_sha256, received_at
            ) VALUES (
                'MOCK', #{callback.notificationId}, #{callback.providerTxnNo},
                #{callback.intentNo}, #{payloadDigest}, #{now}
            )
            """)
    int insertCallback(
            @Param("callback") PaymentCallback callback,
            @Param("payloadDigest") String payloadDigest,
            @Param("now") Instant now
    );

    @Update("""
            UPDATE payment_intent
            SET status = 'SUCCEEDED', provider_txn_no = #{providerTxnNo},
                succeeded_at = #{now}, version = version + 1, updated_at = #{now}
            WHERE intent_no = #{intentNo} AND status = 'PENDING'
            """)
    int succeedPending(
            @Param("intentNo") String intentNo,
            @Param("providerTxnNo") String providerTxnNo,
            @Param("now") Instant now
    );

    @Update("""
            UPDATE payment_intent
            SET status = 'REFUND_PENDING', provider_txn_no = #{providerTxnNo},
                succeeded_at = #{now}, version = version + 1, updated_at = #{now}
            WHERE intent_no = #{intentNo} AND status = 'CLOSED'
            """)
    int markLateSuccess(
            @Param("intentNo") String intentNo,
            @Param("providerTxnNo") String providerTxnNo,
            @Param("now") Instant now
    );

    @Insert("""
            INSERT INTO payment_exception (
                exception_no, intent_no, exception_type, status,
                detail_digest, created_at, resolved_at
            ) VALUES (
                #{exceptionNo}, #{intentNo}, 'LATE_SUCCESS', 'RESOLVED',
                #{detailDigest}, #{now}, #{now}
            )
            """)
    int insertResolvedLateSuccessException(
            @Param("exceptionNo") String exceptionNo,
            @Param("intentNo") String intentNo,
            @Param("detailDigest") String detailDigest,
            @Param("now") Instant now
    );

    @Insert("""
            INSERT INTO refund_attempt (
                refund_request_no, intent_no, provider_txn_no, amount, currency,
                status, attempt_count, created_at, updated_at
            ) VALUES (
                #{refundRequestNo}, #{intent.intentNo}, #{providerTxnNo},
                #{intent.amount}, #{intent.currency}, 'SUCCEEDED', 1, #{now}, #{now}
            )
            ON DUPLICATE KEY UPDATE refund_request_no = refund_attempt.refund_request_no
            """)
    int insertSuccessfulRefund(
            @Param("refundRequestNo") String refundRequestNo,
            @Param("intent") PaymentIntent intent,
            @Param("providerTxnNo") String providerTxnNo,
            @Param("now") Instant now
    );

    @Update("""
            UPDATE payment_intent
            SET status = 'REFUNDED', refunded_at = #{now},
                version = version + 1, updated_at = #{now}
            WHERE intent_no = #{intentNo} AND status = 'REFUND_PENDING'
            """)
    int markRefunded(@Param("intentNo") String intentNo, @Param("now") Instant now);

    @Insert("""
            INSERT INTO outbox_event (
                event_id, aggregate_id, event_type, exchange_name, routing_key,
                payload_json, status, attempt_count, next_attempt_at, created_at
            ) VALUES (
                #{eventId}, #{aggregateId}, #{eventType}, 'linkverse.events', 'payment.fact',
                #{payloadJson}, 'PENDING', 0, #{now}, #{now}
            )
            """)
    int insertOutbox(
            @Param("eventId") String eventId,
            @Param("aggregateId") String aggregateId,
            @Param("eventType") String eventType,
            @Param("payloadJson") String payloadJson,
            @Param("now") Instant now
    );

    @Select("""
            SELECT id AS id, event_id AS eventId, exchange_name AS exchange,
                   routing_key AS routingKey, payload_json AS payloadJson,
                   attempt_count AS attemptCount, created_at AS createdAt
            FROM outbox_event
            WHERE (status = 'PENDING' OR (status = 'SENDING' AND locked_until <= #{now}))
              AND next_attempt_at <= #{now}
            ORDER BY id LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxMessage> claim(@Param("now") Instant now, @Param("limit") int limit);

    @Update("""
            UPDATE outbox_event
            SET status = 'SENDING', locked_until = #{lockedUntil}
            WHERE id = #{id} AND status IN ('PENDING', 'SENDING')
            """)
    int markSending(@Param("id") long id, @Param("lockedUntil") Instant lockedUntil);

    @Update("""
            UPDATE outbox_event
            SET status = 'PUBLISHED', published_at = #{publishedAt}, locked_until = NULL
            WHERE id = #{id} AND status = 'SENDING'
            """)
    int markPublished(@Param("id") long id, @Param("publishedAt") Instant publishedAt);

    @Update("""
            UPDATE outbox_event
            SET status = #{status}, attempt_count = #{attempts},
                next_attempt_at = #{nextAttemptAt}, locked_until = NULL,
                last_error_digest = #{errorDigest}
            WHERE id = #{id} AND status = 'SENDING'
            """)
    int markFailed(
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
            FROM outbox_event WHERE event_id = #{eventId}
            """)
    OutboxEventView selectOutboxByEventId(String eventId);

    @Update("""
            UPDATE outbox_event
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
            FROM outbox_event
            """)
    OutboxStats selectStats(Instant now);
}
