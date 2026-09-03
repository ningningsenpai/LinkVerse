package ning.linkverse.trade.api.recommendation;

import com.fasterxml.jackson.annotation.JsonProperty;
import ning.linkverse.trade.application.recommendation.RecommendationResult;

import java.util.List;

/**
 * RecommendationResponse 返回请求、模型和降级状态，便于闭环追溯。
 *
 * @author ning
 * @date 2026-09-03
 */
public record RecommendationResponse(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("model_version") String modelVersion,
        boolean fallback,
        List<RecommendationItemResponse> items
) {
    public static RecommendationResponse from(RecommendationResult result) {
        return new RecommendationResponse(
                result.requestId(), result.modelVersion(), result.fallback(),
                result.items().stream().map(RecommendationItemResponse::from).toList()
        );
    }
}
