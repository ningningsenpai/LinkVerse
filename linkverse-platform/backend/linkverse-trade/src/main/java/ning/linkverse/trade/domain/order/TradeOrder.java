package ning.linkverse.trade.domain.order;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * TradeOrder 聚合订单事实及其单行商品快照。
 *
 * @author ning
 * @date 2026-08-24
 */
public record TradeOrder(
        long id,
        String orderNo,
        long buyerId,
        long sellerId,
        String idempotencyKey,
        String requestFingerprint,
        String status,
        BigDecimal totalAmount,
        String currency,
        Instant expireAt,
        Instant createdAt,
        OrderItemSnapshot item
) {
}
