package ning.linkverse.trade.domain.recommendation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * RecommendationCandidate 表示 Python 服务返回的未经 Trade 权威过滤候选。
 *
 * @author ning
 * @date 2026-09-03
 */
public record RecommendationCandidate(
        @JsonProperty("object_id") String objectId,
        BigDecimal score,
        List<String> sources,
        @JsonProperty("reason_code") String reasonCode
) {
}
