package ning.linkverse.trade.api.listing;

import com.fasterxml.jackson.annotation.JsonProperty;
import ning.linkverse.trade.domain.listing.BookListing;

import java.time.Instant;

/**
 * ListingResponse 定义商品详情的外部响应字段。
 *
 * @author ning
 * @date 2026-08-24
 */
public record ListingResponse(
        @JsonProperty("listing_id") long listingId,
        @JsonProperty("seller_id") long sellerId,
        String title,
        String author,
        String description,
        @JsonProperty("unit_price") String unitPrice,
        String currency,
        String status,
        int available,
        @JsonProperty("updated_at") Instant updatedAt
) {

    public static ListingResponse from(BookListing listing) {
        return new ListingResponse(
                listing.id(),
                listing.sellerId(),
                listing.title(),
                listing.author(),
                listing.description(),
                listing.unitPrice().setScale(4).toPlainString(),
                listing.currency(),
                listing.status(),
                listing.available(),
                listing.updatedAt()
        );
    }
}
