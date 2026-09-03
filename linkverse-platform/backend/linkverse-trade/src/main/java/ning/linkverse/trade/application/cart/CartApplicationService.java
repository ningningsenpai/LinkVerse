package ning.linkverse.trade.application.cart;

import ning.linkverse.core.error.PlatformException;
import ning.linkverse.trade.domain.TradeErrorCode;
import ning.linkverse.trade.domain.TradeRepository;
import ning.linkverse.trade.domain.behavior.BehaviorAction;
import ning.linkverse.trade.domain.behavior.TradeBehaviorEvent;
import ning.linkverse.trade.domain.cart.CartItem;
import ning.linkverse.trade.domain.listing.BookListing;
import ning.linkverse.trade.domain.recommendation.RecommendationDelivery;
import ning.linkverse.trade.domain.recommendation.RecommendationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CartApplicationService 管理购物车权限，并仅在首次加购成功时写入 CART_ADD 事实。
 *
 * @author ning
 * @date 2026-09-03
 */
@Service
public class CartApplicationService {

    private final TradeRepository tradeRepository;
    private final RecommendationRepository recommendationRepository;
    private final Clock clock;

    public CartApplicationService(
            TradeRepository tradeRepository,
            RecommendationRepository recommendationRepository,
            Clock clock
    ) {
        this.tradeRepository = tradeRepository;
        this.recommendationRepository = recommendationRepository;
        this.clock = clock;
    }

    public List<CartItem> find(long userId) {
        return recommendationRepository.findCartItems(userId);
    }

    @Transactional
    public List<CartItem> add(long userId, long listingId, Long deliveryId) {
        BookListing listing = tradeRepository.findListing(listingId)
                .orElseThrow(() -> new PlatformException(TradeErrorCode.LISTING_NOT_FOUND));
        if (!listing.isOnSale()) {
            throw new PlatformException(TradeErrorCode.LISTING_NOT_ON_SALE);
        }
        if (listing.available() < 1) {
            throw new PlatformException(TradeErrorCode.OUT_OF_STOCK);
        }
        if (listing.sellerId() == userId) {
            throw new PlatformException(TradeErrorCode.SELF_PURCHASE_NOT_ALLOWED);
        }
        Instant now = clock.instant();
        if (tradeRepository.isSeckillProtected(listingId, now)) {
            throw new PlatformException(TradeErrorCode.SECKILL_LISTING_PROTECTED);
        }
        RecommendationDelivery delivery = validateDelivery(deliveryId, userId, listingId);
        if (recommendationRepository.addCartItem(userId, listingId, deliveryId, now)) {
            recommendationRepository.insertBehavior(new TradeBehaviorEvent(
                    compactUuid(), userId, listingId, BehaviorAction.CART_ADD, now, now,
                    delivery == null ? null : delivery.id(),
                    delivery == null ? null : delivery.requestId(),
                    null,
                    delivery == null ? null : delivery.position(),
                    delivery == null ? null : delivery.sources().getFirst(),
                    delivery == null ? null : delivery.modelVersion(),
                    null, null, "REAL"
            ));
        }
        return recommendationRepository.findCartItems(userId);
    }

    @Transactional
    public void delete(long userId, long listingId) {
        if (!recommendationRepository.deleteCartItem(userId, listingId)) {
            throw new PlatformException(TradeErrorCode.CART_ITEM_NOT_FOUND);
        }
    }

    private RecommendationDelivery validateDelivery(Long deliveryId, long userId, long listingId) {
        if (deliveryId == null) {
            return null;
        }
        return recommendationRepository.findDelivery(deliveryId, userId, listingId)
                .orElseThrow(() -> new PlatformException(TradeErrorCode.RECOMMENDATION_DELIVERY_INVALID));
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
