package ning.linkverse.trade.domain.seckill;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SeckillCampaign 是秒杀准入所需的权威活动与商品快照。
 *
 * @author ning
 * @date 2026-08-24
 */
public record SeckillCampaign(
        long id,
        String campaignNo,
        long listingId,
        long sellerId,
        String title,
        String author,
        BigDecimal price,
        String currency,
        long version,
        String status,
        int initialStock,
        Instant startsAt,
        Instant endsAt
) {

    public boolean accepts(Instant now) {
        return "ENABLED".equals(status) && !now.isBefore(startsAt) && now.isBefore(endsAt);
    }
}
