package ning.linkverse.trade.domain.behavior;

/**
 * OrderBehaviorTarget 提供支付事实转换为用户—商品行为所需的最小订单投影。
 *
 * @author ning
 * @date 2026-09-03
 */
public record OrderBehaviorTarget(
        long userId,
        long listingId,
        Long recommendationDeliveryId,
        String requestId,
        Integer position,
        String source,
        String modelVersion
) {
}
