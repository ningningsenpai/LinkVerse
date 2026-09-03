package ning.linkverse.trade.api.recommendation;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * RecommendationEventRequest 只接收客户端可证明的 delivery、行为和会话字段。
 *
 * @author ning
 * @date 2026-09-03
 */
public record RecommendationEventRequest(
        @NotNull @Min(1) @JsonProperty("recommendation_delivery_id") Long recommendationDeliveryId,
        @NotNull @Min(1) @JsonProperty("listing_id") Long listingId,
        @NotBlank String action,
        @NotBlank @Size(max = 64) @JsonProperty("session_id") String sessionId
) {
}
