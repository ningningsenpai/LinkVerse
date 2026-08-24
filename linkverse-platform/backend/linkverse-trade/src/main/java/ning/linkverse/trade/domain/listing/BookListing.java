package ning.linkverse.trade.domain.listing;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * BookListing 表示带权威库存的可查询商品事实。
 *
 * @author ning
 * @date 2026-08-24
 */
public record BookListing(
        long id,
        long sellerId,
        String title,
        String author,
        String description,
        BigDecimal unitPrice,
        String currency,
        String status,
        long version,
        int available,
        Instant updatedAt
) {

    public boolean isOnSale() {
        return "ON_SALE".equals(status);
    }
}
