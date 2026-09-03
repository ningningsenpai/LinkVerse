package ning.linkverse.trade.application.recommendation;

import ning.linkverse.trade.domain.listing.BookListing;

import java.math.BigDecimal;
import java.util.List;

/**
 * DeliveredRecommendation 聚合对外展示所需的 delivery 标识、商品事实和推荐解释。
 *
 * @author ning
 * @date 2026-09-03
 */
public record DeliveredRecommendation(
        long deliveryId,
        BookListing listing,
        int position,
        BigDecimal score,
        List<String> sources,
        String reasonCode,
        String reason
) {
}
