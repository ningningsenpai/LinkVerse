package ning.linkverse.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * PaymentIntent 是 Payment Schema 内的一单唯一支付事实。
 *
 * @author ning
 * @date 2026-08-24
 */
public record PaymentIntent(
        long id,
        String intentNo,
        String orderNo,
        long buyerId,
        long merchantId,
        BigDecimal amount,
        String currency,
        String provider,
        String status,
        String providerTxnNo,
        Instant expireAt,
        Instant succeededAt,
        Instant closedAt,
        Instant refundedAt,
        Instant createdAt
) {

    public boolean sameRequest(CreatePaymentIntent command) {
        return orderNo.equals(command.orderNo())
                && buyerId == command.buyerId()
                && merchantId == command.merchantId()
                && amount.compareTo(command.amount()) == 0
                && currency.equals(command.currency())
                && expireAt.equals(command.expireAt());
    }
}
