package ning.linkverse.payment.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * CreatePaymentIntentRequest 是 Trade 服务提交的不可变订单支付快照。
 *
 * @author ning
 * @date 2026-08-24
 */
public record CreatePaymentIntentRequest(
        @NotBlank @Pattern(regexp = "[0-9a-f]{32}") @JsonProperty("order_no") String orderNo,
        @Min(1) @JsonProperty("buyer_id") long buyerId,
        @Min(1) @JsonProperty("merchant_id") long merchantId,
        @NotNull @DecimalMin("0.0001") BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @JsonProperty("expire_at") Instant expireAt
) {
}
