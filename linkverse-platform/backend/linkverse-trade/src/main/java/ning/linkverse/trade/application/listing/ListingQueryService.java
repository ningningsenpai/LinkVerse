package ning.linkverse.trade.application.listing;

import ning.linkverse.core.error.PlatformException;
import ning.linkverse.trade.domain.TradeErrorCode;
import ning.linkverse.trade.domain.TradeRepository;
import ning.linkverse.trade.domain.listing.BookListing;
import org.springframework.stereotype.Service;

/**
 * ListingQueryService 查询带权威库存的商品详情。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class ListingQueryService {

    private final TradeRepository tradeRepository;

    public ListingQueryService(TradeRepository tradeRepository) {
        this.tradeRepository = tradeRepository;
    }

    public BookListing find(long listingId) {
        return tradeRepository.findListing(listingId)
                .orElseThrow(() -> new PlatformException(TradeErrorCode.LISTING_NOT_FOUND));
    }
}
