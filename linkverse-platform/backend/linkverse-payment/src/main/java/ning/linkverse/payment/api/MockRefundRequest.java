package ning.linkverse.payment.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * MockRefundRequest 指定本地闭环验证所需的用户退款原因。
 *
 * @author ning
 * @date 2026-09-03
 */
public record MockRefundRequest(@NotBlank @JsonProperty("reason_code") String reasonCode) {
}
