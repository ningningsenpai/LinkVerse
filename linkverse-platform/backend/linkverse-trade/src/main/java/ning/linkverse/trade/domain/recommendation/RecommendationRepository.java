package ning.linkverse.trade.domain.recommendation;

import ning.linkverse.trade.domain.behavior.OrderBehaviorTarget;
import ning.linkverse.trade.domain.behavior.TradeBehaviorEvent;
import ning.linkverse.trade.domain.cart.CartItem;
import ning.linkverse.trade.domain.listing.BookListing;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * RecommendationRepository 隔离推荐 delivery、业务过滤、购物车与行为事实的持久化细节。
 *
 * @author ning
 * @date 2026-09-03
 */
public interface RecommendationRepository {

    List<BookListing> findEligibleListings(long userId, Collection<Long> listingIds, Instant now);

    List<BookListing> findFallbackListings(long userId, Collection<Long> excludedIds, Instant now, int limit);

    List<Long> findRecentPositiveListingIds(long userId, Instant now);

    RecommendationDelivery insertDelivery(RecommendationDelivery delivery);

    Optional<RecommendationDelivery> findDelivery(long deliveryId, long userId, long listingId);

    List<CartItem> findCartItems(long userId);

    boolean addCartItem(long userId, long listingId, Long deliveryId, Instant now);

    boolean deleteCartItem(long userId, long listingId);

    void insertBehavior(TradeBehaviorEvent event);

    Optional<OrderBehaviorTarget> findOrderBehaviorTarget(String orderNo);
}
