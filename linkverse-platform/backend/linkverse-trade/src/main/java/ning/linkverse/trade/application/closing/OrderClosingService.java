package ning.linkverse.trade.application.closing;

import ning.linkverse.trade.application.payment.PaymentInternalClient;
import ning.linkverse.trade.application.payment.PaymentInternalRequest;
import ning.linkverse.trade.application.payment.PaymentInternalResponse;
import ning.linkverse.trade.domain.TradeRepository;
import ning.linkverse.trade.domain.order.DueOrderCandidate;
import ning.linkverse.trade.domain.order.PayableOrder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * OrderClosingService 先认领订单，再以 Payment 权威状态裁决支付或关单。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class OrderClosingService {

    private final TradeRepository repository;
    private final PaymentInternalClient paymentClient;
    private final OrderSettlementService settlementService;
    private final Clock clock;

    public OrderClosingService(
            TradeRepository repository,
            PaymentInternalClient paymentClient,
            OrderSettlementService settlementService,
            Clock clock
    ) {
        this.repository = repository;
        this.paymentClient = paymentClient;
        this.settlementService = settlementService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${linkverse.trade.closing.fixed-delay:5000}")
    public void closeDueOrders() {
        Instant now = clock.instant();
        List<DueOrderCandidate> due = repository.findDuePending(now, null, null, 50);
        for (DueOrderCandidate candidate : due) {
            reconcile(candidate.orderNo());
        }
    }

    public boolean reconcile(String orderNo) {
        Instant now = clock.instant();
        PayableOrder order = repository.findPayableByOrder(orderNo).orElse(null);
        if (order == null) {
            return true;
        }
        if ("PENDING_PAYMENT".equals(order.status()) && order.expireAt().compareTo(now) <= 0) {
            if (!repository.claimClosing(orderNo, now)) {
                return true;
            }
            order = repository.findPayableByOrder(orderNo).orElseThrow();
        }
        if (!"CLOSING".equals(order.status())) {
            return true;
        }
        try {
            PaymentInternalResponse payment = paymentClient.close(request(order));
            if ("SUCCEEDED".equals(payment.status())) {
                return settlementService.paid(orderNo, payment.intentNo(), now);
            } else if ("CLOSED".equals(payment.status()) || "REFUNDED".equals(payment.status())) {
                return settlementService.closed(orderNo, now);
            }
            repository.releaseClosing(orderNo, now);
            return false;
        } catch (RuntimeException exception) {
            repository.releaseClosing(orderNo, now);
            return false;
        }
    }

    private PaymentInternalRequest request(PayableOrder order) {
        return new PaymentInternalRequest(
                order.orderNo(),
                order.buyerId(),
                order.sellerId(),
                order.amount(),
                order.currency(),
                order.expireAt()
        );
    }
}
