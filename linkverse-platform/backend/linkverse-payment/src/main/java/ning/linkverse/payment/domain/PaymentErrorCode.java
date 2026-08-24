package ning.linkverse.payment.domain;

import ning.linkverse.core.error.ErrorCode;

/**
 * PaymentErrorCode 定义支付服务可安全返回的稳定错误。
 *
 * @author ning
 * @date 2026-08-24
 */
public enum PaymentErrorCode implements ErrorCode {

    INTENT_NOT_FOUND("PAYMENT_INTENT_NOT_FOUND", 404, "支付意图不存在"),
    INTENT_CONFLICT("PAYMENT_INTENT_CONFLICT", 409, "订单支付参数与已有意图不一致"),
    INTENT_EXPIRED("PAYMENT_INTENT_EXPIRED", 409, "订单支付窗口已关闭"),
    INTENT_NOT_PAYABLE("PAYMENT_INTENT_NOT_PAYABLE", 409, "支付意图当前不可支付"),
    CALLBACK_SIGNATURE_INVALID("PAYMENT_CALLBACK_SIGNATURE_INVALID", 401, "支付回调签名无效"),
    CALLBACK_EXPIRED("PAYMENT_CALLBACK_EXPIRED", 400, "支付回调时间戳已过期"),
    CALLBACK_MISMATCH("PAYMENT_CALLBACK_MISMATCH", 400, "支付回调业务字段不匹配"),
    CALLBACK_REUSED("PAYMENT_CALLBACK_REUSED", 409, "支付通知编号已用于其他回调");

    private final String code;
    private final int status;
    private final String defaultMessage;

    PaymentErrorCode(String code, int status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
