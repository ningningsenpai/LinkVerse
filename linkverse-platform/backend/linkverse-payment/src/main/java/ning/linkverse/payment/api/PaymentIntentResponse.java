package ning.linkverse.payment.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import ning.linkverse.payment.domain.PaymentIntent;

import java.time.Instant;

/**
 * PaymentIntentResponse 隐藏内部主键并稳定返回金额与退款终态。
 *
 * @author ning
 * @date 2026-08-24
 */
public record PaymentIntentResponse(
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

    public static PaymentIntentResponse from(PaymentIntent intent) {
        return new PaymentIntentResponse(
                intent.intentNo(),
                intent.orderNo(),
                intent.amount().setScale(4).toPlainString(),
                intent.currency(),
                intent.provider(),
                intent.status(),
                intent.expireAt(),
                intent.succeededAt(),
                intent.closedAt(),
                intent.refundedAt()
        );
    }
}
