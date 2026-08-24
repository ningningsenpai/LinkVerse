package ning.linkverse.trade.domain.order;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * PayableOrder 是 Trade 向 Payment 提供的服务端订单支付快照。
 *
 * @author ning
 * @date 2026-08-24
 */
public record PayableOrder(
        String orderNo,
        long buyerId,
        long sellerId,
        BigDecimal amount,
        String currency,
        String status,
        Instant expireAt,
        String paymentIntentNo
) {
}
