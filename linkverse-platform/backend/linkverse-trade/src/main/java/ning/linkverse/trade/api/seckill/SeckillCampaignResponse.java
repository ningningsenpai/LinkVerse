package ning.linkverse.trade.api.seckill;

import com.fasterxml.jackson.annotation.JsonProperty;
import ning.linkverse.trade.domain.seckill.SeckillCampaign;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SeckillCampaignResponse 返回活动与商品只读快照，不暴露内部库存镜像。
 *
 * @author ning
 * @date 2026-08-24
 */
public record SeckillCampaignResponse(
        @JsonProperty("campaign_id") long campaignId,
        @JsonProperty("listing_id") long listingId,
        String title,
        BigDecimal price,
        String currency,
        String status,
        @JsonProperty("starts_at") Instant startsAt,
        @JsonProperty("ends_at") Instant endsAt
) {

    static SeckillCampaignResponse from(SeckillCampaign campaign, Instant now) {
        String displayStatus = campaign.accepts(now) ? "OPEN" : "CLOSED";
        return new SeckillCampaignResponse(
                campaign.id(), campaign.listingId(), campaign.title(), campaign.price(), campaign.currency(),
                displayStatus, campaign.startsAt(), campaign.endsAt());
    }
}
