package ning.linkverse.trade.infrastructure.persistence;

import ning.linkverse.trade.domain.TradeRepository;
import ning.linkverse.trade.domain.listing.BookListing;
import ning.linkverse.trade.domain.order.DueOrderCandidate;
import ning.linkverse.trade.domain.order.NewOrder;
import ning.linkverse.trade.domain.order.PayableOrder;
import ning.linkverse.trade.domain.order.TradeOrder;
import ning.linkverse.trade.infrastructure.persistence.entity.TradeOrderEntity;
import ning.linkverse.trade.infrastructure.persistence.mapper.TradeMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MyBatisPlusTradeRepository 使用 Trade Schema 完成商品、库存和订单的持久化访问。
 *
 * @author ning
 * @date 2026-08-31
 */
@Repository
public class MyBatisPlusTradeRepository implements TradeRepository {

    private final TradeMapper mapper;

    public MyBatisPlusTradeRepository(TradeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<BookListing> findListing(long listingId) {
        return Optional.ofNullable(mapper.selectListing(listingId));
    }

    @Override
    public Optional<TradeOrder> findByBuyerAndIdempotencyKey(long buyerId, String idempotencyKey) {
        return Optional.ofNullable(mapper.selectOrderByBuyerAndIdempotencyKey(buyerId, idempotencyKey))
                .map(TradeMapper.TradeOrderRow::toDomain);
    }

    @Override
    public Optional<TradeOrder> findByOrderNoAndBuyer(String orderNo, long buyerId) {
        return Optional.ofNullable(mapper.selectOrderByNoAndBuyer(orderNo, buyerId))
                .map(TradeMapper.TradeOrderRow::toDomain);
    }

    @Override
    public Optional<PayableOrder> findPayableByOrder(String orderNo) {
        return Optional.ofNullable(mapper.selectPayableByOrder(orderNo));
    }

    @Override
    public Optional<PayableOrder> findPayableByOrderAndBuyer(String orderNo, long buyerId) {
        return Optional.ofNullable(mapper.selectPayableByOrderAndBuyer(orderNo, buyerId));
    }

    @Override
    public long insertOrder(NewOrder order) {
        TradeOrderEntity entity = TradeOrderEntity.pending(order);
        mapper.insert(entity);
        if (entity.id() == null) {
            throw new IllegalStateException("创建订单后未取得主键");
        }
        return entity.id();
    }

    @Override
    public boolean decrementStock(long listingId, Instant now) {
        return mapper.decrementStock(listingId, now) == 1;
    }

    @Override
    public boolean isSeckillProtected(long listingId, Instant now) {
        return mapper.countActiveSeckillCampaign(listingId, now) > 0;
    }

    @Override
    public void insertOrderItem(long orderId, NewOrder order) {
        mapper.insertOrderItem(orderId, order);
    }

    @Override
    public boolean attachReservation(String orderNo, long reservationId, Instant now) {
        return mapper.attachReservation(orderNo, reservationId, now) == 1;
    }

    @Override
    public boolean attachPaymentIntent(String orderNo, long buyerId, String intentNo, Instant now) {
        return mapper.attachPaymentIntent(orderNo, buyerId, intentNo, now) == 1;
    }

    @Override
    public boolean claimClosing(String orderNo, Instant now) {
        return mapper.claimClosing(orderNo, now) == 1;
    }

    @Override
    public boolean releaseClosing(String orderNo, Instant now) {
        return mapper.releaseClosing(orderNo, now) == 1;
    }

    @Override
    public boolean markPaid(String orderNo, String intentNo, Instant paidAt) {
        return mapper.markPaid(orderNo, intentNo, paidAt) == 1;
    }

    @Override
    public boolean closeAndRestoreStock(String orderNo, Instant closedAt) {
        Long listingId = mapper.selectClosingListingForUpdate(orderNo);
        if (listingId == null || mapper.closeOrder(orderNo, closedAt) != 1) {
            return false;
        }
        mapper.restoreStock(listingId, closedAt);
        return true;
    }

    @Override
    public boolean recordConsumedEvent(
            String consumerName,
            String eventId,
            String eventType,
            Instant consumedAt
    ) {
        try {
            return mapper.insertConsumedEvent(consumerName, eventId, eventType, consumedAt) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public List<DueOrderCandidate> findDuePending(
            Instant cutoff,
            Instant afterExpireAt,
            Long afterId,
            int limit
    ) {
        return mapper.selectDuePending(cutoff, afterExpireAt, afterId, limit);
    }
}
