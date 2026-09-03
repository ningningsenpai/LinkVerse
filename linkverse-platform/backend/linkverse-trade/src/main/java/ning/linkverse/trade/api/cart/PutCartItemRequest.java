package ning.linkverse.trade.api.cart;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;

/**
 * PutCartItemRequest 允许客户端附带已收到的推荐 delivery 用于归因。
 *
 * @author ning
 * @date 2026-09-03
 */
public record PutCartItemRequest(
        @Min(1) @JsonProperty("recommendation_delivery_id") Long recommendationDeliveryId
) {
}
