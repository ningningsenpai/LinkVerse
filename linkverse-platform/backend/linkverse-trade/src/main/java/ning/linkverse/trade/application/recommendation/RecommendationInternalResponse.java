package ning.linkverse.trade.application.recommendation;

import com.fasterxml.jackson.annotation.JsonProperty;
import ning.linkverse.trade.domain.recommendation.RecommendationCandidate;

import java.util.List;

/**
 * RecommendationInternalResponse 定义推荐服务返回的领域、模型和候选列表。
 *
 * @author ning
 * @date 2026-09-03
 */
public record RecommendationInternalResponse(
        @JsonProperty("request_id") String requestId,
        String domain,
        @JsonProperty("model_version") String modelVersion,
        List<RecommendationCandidate> candidates
) {
}
