package ning.linkverse.trade.domain.cart;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * CartItem 表示当前用户购物车中带商品快照的条目。
 *
 * @author ning
 * @date 2026-09-03
 */
public record CartItem(
        long id,
        long listingId,
        String title,
        String author,
        BigDecimal unitPrice,
        String currency,
        String status,
        int available,
        Long recommendationDeliveryId,
        Instant createdAt,
        Instant updatedAt
) {
}
