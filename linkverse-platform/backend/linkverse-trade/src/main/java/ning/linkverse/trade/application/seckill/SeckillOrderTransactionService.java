package ning.linkverse.trade.application.seckill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.messaging.MessageEnvelope;
import ning.linkverse.core.request.RequestContextKeys;
import ning.linkverse.trade.domain.TradeRepository;
import ning.linkverse.trade.domain.listing.BookListing;
import ning.linkverse.trade.domain.order.NewOrder;
import ning.linkverse.trade.domain.order.OrderItemSnapshot;
import ning.linkverse.trade.domain.seckill.SeckillCampaign;
import ning.linkverse.trade.domain.seckill.SeckillRepository;
import ning.linkverse.trade.domain.seckill.SeckillRequest;
import ning.linkverse.trade.domain.seckill.SeckillReservation;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * SeckillOrderTransactionService 在一个事务内完成消费去重、MySQL 扣库存、建单和结果 Outbox。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class SeckillOrderTransactionService {

    private static final String CONSUMER = "trade-seckill-order";

    private final SeckillRepository seckillRepository;
    private final TradeRepository tradeRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration paymentTimeout;

    public SeckillOrderTransactionService(
            SeckillRepository seckillRepository,
            TradeRepository tradeRepository,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${linkverse.trade.order-payment-timeout:15m}") Duration paymentTimeout
    ) {
        this.seckillRepository = seckillRepository;
        this.tradeRepository = tradeRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.paymentTimeout = paymentTimeout;
    }

    @Transactional
    public void consume(String eventId) {
        SeckillRequest request = seckillRepository.lockRequest(eventId)
                .orElseThrow(() -> new IllegalStateException("秒杀事件尚未形成 MySQL 预约"));
        SeckillReservation reservation = seckillRepository.findReservation(request.reservationNo())
                .orElseThrow(() -> new IllegalStateException("秒杀预约不存在"));
        Instant now = clock.instant();
        if (!seckillRepository.recordConsumed(CONSUMER, eventId, "SeckillRequested", now)) {
            return;
        }
        if (!"PUBLISH_PENDING".equals(reservation.status()) && !"PUBLISHED".equals(reservation.status())) {
            return;
        }
        SeckillCampaign campaign = seckillRepository.findCampaign(request.campaignId())
                .orElseThrow(() -> new IllegalStateException("秒杀活动不存在"));
        if (request.campaignVersion() != campaign.version() || !campaign.accepts(request.occurredAt())) {
            String resultEventId = number();
            seckillRepository.markFailed(
                    reservation.id(), "CAMPAIGN_CHANGED", now, resultEventId,
                    resultJson("SeckillReservationRejected", reservation, null,
                            "CAMPAIGN_CHANGED", now, resultEventId));
            return;
        }
        BookListing listing = tradeRepository.findListing(campaign.listingId())
                .orElseThrow(() -> new IllegalStateException("秒杀商品不存在"));
        String orderNo = number();
        NewOrder order = new NewOrder(
                orderNo,
                request.userId(),
                listing.sellerId(),
                "seckill:" + request.reservationNo(),
                request.requestFingerprint(),
                listing.unitPrice(),
                listing.currency(),
                now.plus(paymentTimeout),
                now,
                new OrderItemSnapshot(
                        listing.id(), listing.sellerId(), listing.version(), listing.title(), listing.author(),
                        listing.unitPrice(), listing.unitPrice(), 1, listing.currency())
        );
        long orderId = tradeRepository.insertOrder(order);
        if (!tradeRepository.decrementStock(listing.id(), now)) {
            // 订单插入与库存扣减在同一事务；先删除未完成订单，再保留失败墓碑和拒绝事件。
            throw new SeckillStockConflictException(reservation, now);
        }
        tradeRepository.insertOrderItem(orderId, order);
        if (!tradeRepository.attachReservation(orderNo, reservation.id(), now)) {
            throw new IllegalStateException("秒杀订单无法关联预约");
        }
        String resultEventId = number();
        seckillRepository.markOrderCreated(
                reservation.id(), orderNo, now, resultEventId,
                resultJson("SeckillReservationSucceeded", reservation, orderNo, null, now, resultEventId));
    }

    @Transactional
    public void recordStockFailure(SeckillStockConflictException conflict) {
        String resultEventId = number();
        seckillRepository.markFailed(
                conflict.reservation().id(), "MYSQL_OUT_OF_STOCK", conflict.occurredAt(), resultEventId,
                resultJson("SeckillReservationRejected", conflict.reservation(), null,
                        "MYSQL_OUT_OF_STOCK", conflict.occurredAt(), resultEventId));
    }

    private String resultJson(
            String eventType,
            SeckillReservation reservation,
            String orderNo,
            String failureCode,
            Instant now
    ) {
        return resultJson(eventType, reservation, orderNo, failureCode, now, number());
    }

    private String resultJson(
            String eventType,
            SeckillReservation reservation,
            String orderNo,
            String failureCode,
            Instant now,
            String eventId
    ) {
        try {
            Map<String, Object> payload = Map.of(
                    "reservation_no", reservation.reservationNo(),
                    "campaign_id", reservation.campaignId(),
                    "campaign_version", reservation.campaignVersion(),
                    "order_no", orderNo == null ? "" : orderNo,
                    "failure_code", failureCode == null ? "" : failureCode
            );
            return objectMapper.writeValueAsString(new MessageEnvelope<>(
                    eventId, eventType, 1, reservation.reservationNo(), now, traceId(), payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("秒杀结果事件无法序列化", exception);
        }
    }

    private String number() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String traceId() {
        String value = MDC.get(RequestContextKeys.TRACE_ID_MDC_KEY);
        return value == null || value.isBlank() ? "no-trace" : value;
    }

    /**
     * 库存冲突异常用于先回滚未完成订单，再在独立事务保存失败墓碑。
     */
    public static final class SeckillStockConflictException extends RuntimeException {

        private final SeckillReservation reservation;
        private final Instant occurredAt;

        private SeckillStockConflictException(SeckillReservation reservation, Instant occurredAt) {
            super("MySQL 权威库存不足");
            this.reservation = reservation;
            this.occurredAt = occurredAt;
        }

        public SeckillReservation reservation() {
            return reservation;
        }

        public Instant occurredAt() {
            return occurredAt;
        }
    }
}
