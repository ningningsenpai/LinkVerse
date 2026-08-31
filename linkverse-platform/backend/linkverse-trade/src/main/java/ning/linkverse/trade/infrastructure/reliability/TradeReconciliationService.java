package ning.linkverse.trade.infrastructure.reliability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * TradeReconciliationService 检测长期订单和预约中间态，只输出差异而不跨域改写事实。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class TradeReconciliationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TradeReconciliationService.class);

    private final TradeReconciliationMapper mapper;
    private final AtomicLong closingOrder = new AtomicLong();
    private final AtomicLong intermediateReservation = new AtomicLong();
    private final AtomicLong reservationOrderMismatch = new AtomicLong();

    public TradeReconciliationService(TradeReconciliationMapper mapper, MeterRegistry meterRegistry) {
        this.mapper = mapper;
        register(meterRegistry, "closing_order", closingOrder);
        register(meterRegistry, "intermediate_reservation", intermediateReservation);
        register(meterRegistry, "reservation_order_mismatch", reservationOrderMismatch);
    }

    @Scheduled(fixedDelayString = "${linkverse.trade.reconciliation-fixed-delay:30000}")
    public Snapshot reconcile() {
        Snapshot snapshot = new Snapshot(
                mapper.countClosingOrders(),
                mapper.countIntermediateReservations(),
                mapper.countReservationOrderMismatches()
        );
        closingOrder.set(snapshot.closingOrder());
        intermediateReservation.set(snapshot.intermediateReservation());
        reservationOrderMismatch.set(snapshot.reservationOrderMismatch());
        if (snapshot.total() > 0) {
            LOGGER.warn(
                    "交易对账发现差异 closing_order={} intermediate_reservation={} reservation_order_mismatch={}",
                    snapshot.closingOrder(), snapshot.intermediateReservation(),
                    snapshot.reservationOrderMismatch());
        }
        return snapshot;
    }

    private void register(MeterRegistry registry, String type, AtomicLong value) {
        Gauge.builder("linkverse.reconciliation.difference", value, AtomicLong::get)
                .tag("domain", "trade")
                .tag("type", type)
                .register(registry);
    }

    public record Snapshot(long closingOrder, long intermediateReservation, long reservationOrderMismatch) {
        public long total() {
            return closingOrder + intermediateReservation + reservationOrderMismatch;
        }
    }
}
