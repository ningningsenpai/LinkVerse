package ning.linkverse.trade.api.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import ning.linkverse.trade.domain.order.OrderItemSnapshot;

/**
 * OrderItemResponse 定义订单中的不可变商品快照。
 *
 * @author ning
 * @date 2026-08-24
 */
public record OrderItemResponse(
        @JsonProperty("listing_id") long listingId,
        @JsonProperty("seller_id") long sellerId,
        String title,
        String author,
        @JsonProperty("unit_price") String unitPrice,
        @JsonProperty("line_amount") String lineAmount,
        int quantity,
        String currency
) {

    static OrderItemResponse from(OrderItemSnapshot item) {
        return new OrderItemResponse(
                item.listingId(),
                item.sellerId(),
                item.listingTitle(),
                item.listingAuthor(),
                item.unitPrice().setScale(4).toPlainString(),
                item.lineAmount().setScale(4).toPlainString(),
                item.quantity(),
                item.currency()
        );
    }
}
