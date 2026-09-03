package ning.linkverse.trade.domain.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * RecommendationDelivery 保存一个已返回给客户端的候选及其模型上下文。
 *
 * @author ning
 * @date 2026-09-03
 */
public record RecommendationDelivery(
        Long id,
        String requestId,
        long userId,
        long listingId,
        int position,
        String modelVersion,
        BigDecimal score,
        List<String> sources,
        String reasonCode,
        String scene,
        String dataSource,
        Instant servedAt
) {
}
