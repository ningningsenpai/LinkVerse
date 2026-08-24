package ning.linkverse.trade.domain;

import ning.linkverse.trade.domain.listing.BookListing;
import ning.linkverse.trade.domain.order.DueOrderCandidate;
import ning.linkverse.trade.domain.order.NewOrder;
import ning.linkverse.trade.domain.order.PayableOrder;
import ning.linkverse.trade.domain.order.TradeOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * TradeRepository 定义普通交易所需的最小事实读写契约。
 *
 * @author ning
 * @date 2026-08-24
 */
public interface TradeRepository {

    Optional<BookListing> findListing(long listingId);

    Optional<TradeOrder> findByBuyerAndIdempotencyKey(long buyerId, String idempotencyKey);

    Optional<TradeOrder> findByOrderNoAndBuyer(String orderNo, long buyerId);

    Optional<PayableOrder> findPayableByOrder(String orderNo);

    Optional<PayableOrder> findPayableByOrderAndBuyer(String orderNo, long buyerId);

    long insertOrder(NewOrder order);

    boolean decrementStock(long listingId, Instant now);

    boolean isSeckillProtected(long listingId, Instant now);

    void insertOrderItem(long orderId, NewOrder order);

    boolean attachReservation(String orderNo, long reservationId, Instant now);

    boolean attachPaymentIntent(String orderNo, long buyerId, String intentNo, Instant now);

    boolean claimClosing(String orderNo, Instant now);

    boolean releaseClosing(String orderNo, Instant now);

    boolean markPaid(String orderNo, String intentNo, Instant paidAt);

    boolean closeAndRestoreStock(String orderNo, Instant closedAt);

    boolean recordConsumedEvent(String consumerName, String eventId, String eventType, Instant consumedAt);

    List<DueOrderCandidate> findDuePending(
            Instant cutoff,
            Instant afterExpireAt,
            Long afterId,
            int limit
    );
}
