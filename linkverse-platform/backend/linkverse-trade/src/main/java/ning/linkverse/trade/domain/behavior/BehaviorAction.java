package ning.linkverse.trade.domain.behavior;

/**
 * BehaviorAction 限定 Trade 可持久化的行为事实。
 *
 * @author ning
 * @date 2026-09-03
 */
public enum BehaviorAction {
    IMPRESSION,
    DETAIL_OPEN,
    CART_ADD,
    ORDER_CREATED,
    PAYMENT_SUCCEEDED,
    REFUNDED
}
