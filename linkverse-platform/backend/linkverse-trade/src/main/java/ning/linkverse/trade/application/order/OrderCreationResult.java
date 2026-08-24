package ning.linkverse.trade.application.order;

import ning.linkverse.trade.domain.order.TradeOrder;

/**
 * OrderCreationResult 区分新建订单与幂等重放响应。
 *
 * @author ning
 * @date 2026-08-24
 */
public record OrderCreationResult(TradeOrder order, boolean created) {
}
