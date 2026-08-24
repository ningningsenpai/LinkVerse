package ning.linkverse.trade.domain.seckill;

import java.time.Instant;

/**
 * SeckillReservation 保存秒杀请求从排队到订单或失败的可查询状态。
 *
 * @author ning
 * @date 2026-08-24
 */
public record SeckillReservation(
        long id,
        String reservationNo,
        String eventId,
        long campaignId,
        long campaignVersion,
        long userId,
        String idempotencyKey,
        String requestFingerprint,
        String status,
        String orderNo,
        String failureCode,
        Instant createdAt,
        Instant updatedAt
) {
}
