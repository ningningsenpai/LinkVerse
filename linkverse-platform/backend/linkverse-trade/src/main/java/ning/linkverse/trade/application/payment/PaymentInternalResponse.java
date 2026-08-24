package ning.linkverse.trade.application.payment;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * PaymentInternalResponse 是 Trade 使用的最小 Payment 响应契约。
 *
 * @author ning
 * @date 2026-08-24
 */
public record PaymentInternalResponse(
        @JsonProperty("intent_no") String intentNo,
        @JsonProperty("order_no") String orderNo,
        String amount,
        String currency,
        String provider,
        String status,
        @JsonProperty("expire_at") Instant expireAt,
        @JsonProperty("succeeded_at") Instant succeededAt,
        @JsonProperty("closed_at") Instant closedAt,
        @JsonProperty("refunded_at") Instant refundedAt
) {
}
