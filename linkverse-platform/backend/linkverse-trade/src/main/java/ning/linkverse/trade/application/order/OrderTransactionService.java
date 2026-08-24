package ning.linkverse.trade.application.order;

import ning.linkverse.core.error.PlatformException;
import ning.linkverse.trade.domain.TradeErrorCode;
import ning.linkverse.trade.domain.TradeRepository;
import ning.linkverse.trade.domain.listing.BookListing;
import ning.linkverse.trade.domain.order.NewOrder;
import ning.linkverse.trade.domain.order.OrderItemSnapshot;
import ning.linkverse.trade.domain.order.TradeOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * OrderTransactionService 在一个 Trade 本地事务内写订单、条件扣库存和商品快照。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class OrderTransactionService {

    private final TradeRepository tradeRepository;
    private final Clock clock;
    private final Duration paymentTimeout;

    public OrderTransactionService(
            TradeRepository tradeRepository,
            Clock clock,
            @Value("${linkverse.trade.order-payment-timeout:15m}") Duration paymentTimeout
    ) {
        this.tradeRepository = tradeRepository;
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
        BookListing listing = tradeRepository.findListing(listingId)
                .orElseThrow(() -> new PlatformException(TradeErrorCode.LISTING_NOT_FOUND));
        if (!listing.isOnSale()) {
            throw new PlatformException(TradeErrorCode.LISTING_NOT_ON_SALE);
        }
        if (listing.sellerId() == buyerId) {
            throw new PlatformException(TradeErrorCode.SELF_PURCHASE_NOT_ALLOWED);
        }

        Instant now = clock.instant();
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
        return tradeRepository.findByOrderNoAndBuyer(order.orderNo(), buyerId)
                .orElseThrow(() -> new IllegalStateException("创建订单后无法读取订单"));
    }
}
