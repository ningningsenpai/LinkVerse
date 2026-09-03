package ning.linkverse.trade.application.order;

import ning.linkverse.core.error.PlatformException;
import ning.linkverse.trade.domain.TradeErrorCode;
import ning.linkverse.trade.domain.TradeRepository;
import ning.linkverse.trade.domain.behavior.BehaviorAction;
import ning.linkverse.trade.domain.behavior.TradeBehaviorEvent;
import ning.linkverse.trade.domain.listing.BookListing;
import ning.linkverse.trade.domain.order.NewOrder;
import ning.linkverse.trade.domain.order.OrderItemSnapshot;
import ning.linkverse.trade.domain.order.TradeOrder;
import ning.linkverse.trade.domain.recommendation.RecommendationDelivery;
import ning.linkverse.trade.domain.recommendation.RecommendationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * OrderTransactionService 在一个 Trade 本地事务内写订单、条件扣库存和商品快照。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class OrderTransactionService {

    private final TradeRepository tradeRepository;
    private final RecommendationRepository recommendationRepository;
    private final Clock clock;
    private final Duration paymentTimeout;

    @Autowired
    public OrderTransactionService(
            TradeRepository tradeRepository,
            RecommendationRepository recommendationRepository,
            Clock clock,
            @Value("${linkverse.trade.order-payment-timeout:15m}") Duration paymentTimeout
    ) {
        this.tradeRepository = tradeRepository;
        this.recommendationRepository = recommendationRepository;
        this.clock = clock;
        this.paymentTimeout = paymentTimeout;
    }

    public OrderTransactionService(
            TradeRepository tradeRepository,
            Clock clock,
            Duration paymentTimeout
    ) {
        this.tradeRepository = tradeRepository;
        this.recommendationRepository = null;
        this.clock = clock;
        this.paymentTimeout = paymentTimeout;
    }

    @Transactional
    public TradeOrder create(
            long buyerId,
            long listingId,
            String idempotencyKey,
            String fingerprint,
            String orderNo
    ) {
        return create(buyerId, listingId, idempotencyKey, fingerprint, orderNo, null);
    }

    @Transactional
    public TradeOrder create(
            long buyerId,
            long listingId,
            String idempotencyKey,
            String fingerprint,
            String orderNo,
            Long recommendationDeliveryId
    ) {
        BookListing listing = tradeRepository.findListing(listingId)
                .orElseThrow(() -> new PlatformException(TradeErrorCode.LISTING_NOT_FOUND));
        if (!listing.isOnSale()) {
            throw new PlatformException(TradeErrorCode.LISTING_NOT_ON_SALE);
        }
        if (listing.sellerId() == buyerId) {
            throw new PlatformException(TradeErrorCode.SELF_PURCHASE_NOT_ALLOWED);
        }

        Instant now = clock.instant();
        if (tradeRepository.isSeckillProtected(listingId, now)) {
            throw new PlatformException(TradeErrorCode.SECKILL_LISTING_PROTECTED);
        }
        RecommendationDelivery delivery = validateDelivery(recommendationDeliveryId, buyerId, listingId);
        OrderItemSnapshot item = new OrderItemSnapshot(
                listing.id(),
                listing.sellerId(),
                listing.version(),
                listing.title(),
                listing.author(),
                listing.unitPrice(),
                listing.unitPrice(),
                1,
                listing.currency()
        );
        NewOrder order = new NewOrder(
                orderNo,
                buyerId,
                listing.sellerId(),
                idempotencyKey,
                fingerprint,
                recommendationDeliveryId,
                listing.unitPrice(),
                listing.currency(),
                now.plus(paymentTimeout),
                now,
                item
        );

        long orderId = tradeRepository.insertOrder(order);
        // 条件更新是最终库存防线；失败异常必须让刚插入的订单一并回滚。
        if (!tradeRepository.decrementStock(listingId, now)) {
            throw new PlatformException(TradeErrorCode.OUT_OF_STOCK);
        }
        tradeRepository.insertOrderItem(orderId, order);
        if (recommendationRepository != null) {
            recommendationRepository.insertBehavior(new TradeBehaviorEvent(
                    UUID.randomUUID().toString().replace("-", ""),
                    buyerId,
                    listingId,
                    BehaviorAction.ORDER_CREATED,
                    now,
                    now,
                    delivery == null ? null : delivery.id(),
                    delivery == null ? null : delivery.requestId(),
                    null,
                    delivery == null ? null : delivery.position(),
                    delivery == null ? null : delivery.sources().getFirst(),
                    delivery == null ? null : delivery.modelVersion(),
                    orderNo,
                    null,
                    "REAL"
            ));
        }
        return tradeRepository.findByOrderNoAndBuyer(order.orderNo(), buyerId)
                .orElseThrow(() -> new IllegalStateException("创建订单后无法读取订单"));
    }

    private RecommendationDelivery validateDelivery(Long deliveryId, long buyerId, long listingId) {
        if (deliveryId == null) {
            return null;
        }
        if (recommendationRepository == null) {
            throw new IllegalStateException("当前订单事务未配置推荐归因仓库");
        }
        return recommendationRepository.findDelivery(deliveryId, buyerId, listingId)
                .orElseThrow(() -> new PlatformException(TradeErrorCode.RECOMMENDATION_DELIVERY_INVALID));
    }
}
