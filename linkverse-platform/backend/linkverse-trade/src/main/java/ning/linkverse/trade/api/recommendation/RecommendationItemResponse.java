package ning.linkverse.trade.api.recommendation;

import com.fasterxml.jackson.annotation.JsonProperty;
import ning.linkverse.trade.application.recommendation.DeliveredRecommendation;

import java.util.List;

/**
 * RecommendationItemResponse 展示可与后续行为关联的单个商品推荐。
 *
 * @author ning
 * @date 2026-09-03
 */
public record RecommendationItemResponse(
        @JsonProperty("recommendation_delivery_id") long recommendationDeliveryId,
        @JsonProperty("listing_id") long listingId,
        String title,
        String author,
        @JsonProperty("category_code") String categoryCode,
        @JsonProperty("unit_price") String unitPrice,
        String currency,
        int position,
        String score,
        List<String> sources,
        @JsonProperty("reason_code") String reasonCode,
        String reason
) {
    public static RecommendationItemResponse from(DeliveredRecommendation item) {
        return new RecommendationItemResponse(
                item.deliveryId(), item.listing().id(), item.listing().title(), item.listing().author(),
                item.listing().categoryCode(), item.listing().unitPrice().setScale(4).toPlainString(),
                item.listing().currency(), item.position(), item.score().toPlainString(), item.sources(),
                item.reasonCode(), item.reason()
        );
    }
}
