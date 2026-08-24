package ning.linkverse.trade.domain.order;

import java.math.BigDecimal;

/**
 * OrderItemSnapshot 保存下单时不可变的商品和金额快照。
 *
 * @author ning
 * @date 2026-08-24
 */
public record OrderItemSnapshot(
        long listingId,
        long sellerId,
        long listingVersion,
        String listingTitle,
        String listingAuthor,
        BigDecimal unitPrice,
        BigDecimal lineAmount,
        int quantity,
        String currency
) {
}
