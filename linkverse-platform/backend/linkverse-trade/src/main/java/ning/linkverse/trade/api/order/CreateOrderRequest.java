package ning.linkverse.trade.api.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * CreateOrderRequest 定义立即购买允许客户端提交的最小字段。
 *
 * @author ning
 * @date 2026-08-24
 */
public record CreateOrderRequest(
        @NotNull @Min(1) @JsonProperty("listing_id") Long listingId,
        @NotNull @Min(1) Integer quantity,
        @Min(1) @JsonProperty("recommendation_delivery_id") Long recommendationDeliveryId
) {
}
