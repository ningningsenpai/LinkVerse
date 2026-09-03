package ning.linkverse.trade.api.cart;

import com.fasterxml.jackson.annotation.JsonProperty;
import ning.linkverse.trade.domain.cart.CartItem;

import java.time.Instant;

/**
 * CartItemResponse 定义购物车中单个商品的对外快照。
 *
 * @author ning
 * @date 2026-09-03
 */
public record CartItemResponse(
        @JsonProperty("listing_id") long listingId,
        String title,
        String author,
        @JsonProperty("unit_price") String unitPrice,
        String currency,
        String status,
        int available,
        @JsonProperty("recommendation_delivery_id") Long recommendationDeliveryId,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
) {
    public static CartItemResponse from(CartItem item) {
        return new CartItemResponse(
                item.listingId(), item.title(), item.author(), item.unitPrice().setScale(4).toPlainString(),
                item.currency(), item.status(), item.available(), item.recommendationDeliveryId(),
                item.createdAt(), item.updatedAt()
        );
    }
}
