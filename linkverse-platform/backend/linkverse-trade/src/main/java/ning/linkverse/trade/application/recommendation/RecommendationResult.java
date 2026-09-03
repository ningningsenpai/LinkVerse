package ning.linkverse.trade.application.recommendation;

import java.util.List;

/**
 * RecommendationResult 表示 Trade 过滤并持久化后的最终推荐结果。
 *
 * @author ning
 * @date 2026-09-03
 */
public record RecommendationResult(
        String requestId,
        String modelVersion,
        boolean fallback,
        List<DeliveredRecommendation> items
) {
}
