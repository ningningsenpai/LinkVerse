package ning.linkverse.trade.application.recommendation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * RecommendationInternalRequest 定义 Trade 调用推荐服务的 v1 契约。
 *
 * @author ning
 * @date 2026-09-03
 */
public record RecommendationInternalRequest(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("user_key") String userKey,
        String domain,
        String scene,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("candidate_count") int candidateCount,
        Map<String, String> context
) {
}
