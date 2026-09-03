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
    ORDER_NOT_FOUND("TRADE_ORDER_NOT_FOUND", 404, "订单不存在"),
    ORDER_PAYMENT_WINDOW_CLOSED("TRADE_ORDER_PAYMENT_WINDOW_CLOSED", 409, "订单支付窗口已关闭"),
    ORDER_PAYMENT_STATE_INVALID("TRADE_ORDER_PAYMENT_STATE_INVALID", 409, "订单当前状态不可创建支付"),
    RECOMMENDATION_DELIVERY_INVALID("TRADE_RECOMMENDATION_DELIVERY_INVALID", 400, "推荐归因不属于当前用户或商品"),
    RECOMMENDATION_EVENT_INVALID("TRADE_RECOMMENDATION_EVENT_INVALID", 400, "推荐行为事件不合法"),
    CART_ITEM_NOT_FOUND("TRADE_CART_ITEM_NOT_FOUND", 404, "购物车条目不存在"),
    SECKILL_CAMPAIGN_NOT_FOUND("TRADE_SECKILL_CAMPAIGN_NOT_FOUND", 404, "秒杀活动不存在"),
    SECKILL_CAMPAIGN_INACTIVE("TRADE_SECKILL_CAMPAIGN_INACTIVE", 409, "秒杀活动当前不可参与"),
    SECKILL_LISTING_PROTECTED("TRADE_SECKILL_LISTING_PROTECTED", 409, "秒杀专用商品不能普通下单"),
    SECKILL_SOLD_OUT("TRADE_SECKILL_SOLD_OUT", 409, "秒杀库存已售罄"),
    SECKILL_DEPENDENCY_UNAVAILABLE("TRADE_SECKILL_DEPENDENCY_UNAVAILABLE", 503, "秒杀通道暂时不可用"),
    SECKILL_REQUEST_PROCESSING("TRADE_SECKILL_REQUEST_PROCESSING", 409, "秒杀请求正在恢复，请稍后查询"),
    SECKILL_RESERVATION_NOT_FOUND("TRADE_SECKILL_RESERVATION_NOT_FOUND", 404, "秒杀预约不存在");

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
