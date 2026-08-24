package ning.linkverse.payment.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * PaymentCallback 定义 Mock Provider 签名覆盖的完整业务字段。
 *
 * @author ning
 * @date 2026-08-24
 */
public record PaymentCallback(
        @JsonProperty("notification_id") String notificationId,
        @JsonProperty("provider_txn_no") String providerTxnNo,
        @JsonProperty("intent_no") String intentNo,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("merchant_id") long merchantId,
        BigDecimal amount,
        String currency,
        String status
) {
}
