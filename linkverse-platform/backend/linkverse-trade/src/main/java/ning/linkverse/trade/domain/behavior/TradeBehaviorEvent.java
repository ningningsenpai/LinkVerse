package ning.linkverse.trade.domain.behavior;

import java.time.Instant;

/**
 * TradeBehaviorEvent 表示用于训练回放的不可变业务事实。
 *
 * @author ning
 * @date 2026-09-03
 */
public record TradeBehaviorEvent(
        String eventId,
        long userId,
        long listingId,
        BehaviorAction action,
        Instant eventTime,
        Instant ingestedAt,
        Long recommendationDeliveryId,
        String requestId,
        String sessionId,
        Integer position,
        String source,
        String modelVersion,
        String orderNo,
        String refundReasonCode,
        String dataSource
) {
}
