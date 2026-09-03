package ning.linkverse.trade.domain.order;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * NewOrder 携带交易服务生成的新订单写入参数。
 *
 * @author ning
 * @date 2026-08-24
 */
public record NewOrder(
        String orderNo,
        long buyerId,
        long sellerId,
        String idempotencyKey,
        String requestFingerprint,
        Long recommendationDeliveryId,
        BigDecimal totalAmount,
        String currency,
        Instant expireAt,
        Instant now,
        OrderItemSnapshot item
) {

    public NewOrder(
            String orderNo,
            long buyerId,
            long sellerId,
            String idempotencyKey,
            String requestFingerprint,
            BigDecimal totalAmount,
            String currency,
            Instant expireAt,
            Instant now,
            OrderItemSnapshot item
    ) {
        this(orderNo, buyerId, sellerId, idempotencyKey, requestFingerprint, null,
                totalAmount, currency, expireAt, now, item);
    }
}
