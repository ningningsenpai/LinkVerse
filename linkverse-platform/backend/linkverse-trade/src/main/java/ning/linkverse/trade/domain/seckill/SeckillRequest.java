package ning.linkverse.trade.domain.seckill;

import java.time.Instant;

/**
 * SeckillRequest 是 Redis 待发布记录与 MySQL 预约共用的稳定载荷。
 *
 * @author ning
 * @date 2026-08-24
 */
public record SeckillRequest(
        String reservationNo,
        String eventId,
        long campaignId,
        long campaignVersion,
        long userId,
        String idempotencyKey,
        String requestFingerprint,
        Instant occurredAt
) {
}
