package ning.linkverse.trade.domain;

import ning.linkverse.core.error.ErrorCode;

/**
 * TradeErrorCode 定义交易接口可安全返回的稳定错误码。
 *
 * @author ning
 * @date 2026-08-24
 */
public enum TradeErrorCode implements ErrorCode {

    LISTING_NOT_FOUND("TRADE_LISTING_NOT_FOUND", 404, "商品不存在"),
    LISTING_NOT_ON_SALE("TRADE_LISTING_NOT_ON_SALE", 409, "商品当前不可购买"),
    SELF_PURCHASE_NOT_ALLOWED("TRADE_SELF_PURCHASE_NOT_ALLOWED", 409, "不能购买自己发布的商品"),
    OUT_OF_STOCK("TRADE_OUT_OF_STOCK", 409, "商品库存不足"),
    IDEMPOTENCY_KEY_REQUIRED("TRADE_IDEMPOTENCY_KEY_REQUIRED", 400, "缺少幂等键"),
    IDEMPOTENCY_KEY_INVALID("TRADE_IDEMPOTENCY_KEY_INVALID", 400, "幂等键格式不正确"),
    IDEMPOTENCY_KEY_REUSED("TRADE_IDEMPOTENCY_KEY_REUSED", 409, "幂等键已用于其他请求"),
    ORDER_NOT_FOUND("TRADE_ORDER_NOT_FOUND", 404, "订单不存在");

    private final String code;
    private final int status;
    private final String defaultMessage;

    TradeErrorCode(String code, int status, String defaultMessage) {
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
