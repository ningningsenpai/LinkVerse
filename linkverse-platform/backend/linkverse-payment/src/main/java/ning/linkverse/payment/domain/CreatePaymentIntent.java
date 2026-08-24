package ning.linkverse.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * CreatePaymentIntent 保存 Trade 已校验的订单支付快照。
 *
 * @author ning
 * @date 2026-08-24
 */
public record CreatePaymentIntent(
        String orderNo,
        long buyerId,
        long merchantId,
        BigDecimal amount,
        String currency,
        Instant expireAt
) {
}
