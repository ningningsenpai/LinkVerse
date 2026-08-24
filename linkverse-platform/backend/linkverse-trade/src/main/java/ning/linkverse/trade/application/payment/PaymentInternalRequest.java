package ning.linkverse.trade.application.payment;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * PaymentInternalRequest 只由 Trade 的权威订单快照构造。
 *
 * @author ning
 * @date 2026-08-24
 */
public record PaymentInternalRequest(
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("buyer_id") long buyerId,
        @JsonProperty("merchant_id") long merchantId,
        BigDecimal amount,
        String currency,
        @JsonProperty("expire_at") Instant expireAt
) {
}
