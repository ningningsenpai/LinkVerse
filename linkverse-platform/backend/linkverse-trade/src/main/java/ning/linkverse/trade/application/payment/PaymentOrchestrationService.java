package ning.linkverse.trade.application.payment;

import ning.linkverse.core.error.PlatformException;
import ning.linkverse.trade.domain.TradeErrorCode;
import ning.linkverse.trade.domain.TradeRepository;
import ning.linkverse.trade.domain.order.PayableOrder;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * PaymentOrchestrationService 校验订单后以服务端快照创建唯一支付意图。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class PaymentOrchestrationService {

    private final TradeRepository repository;
    private final PaymentInternalClient paymentClient;
    private final Clock clock;

    public PaymentOrchestrationService(
            TradeRepository repository,
            PaymentInternalClient paymentClient,
            Clock clock
    ) {
        this.repository = repository;
        this.paymentClient = paymentClient;
        this.clock = clock;
    }

    public PaymentInternalResponse create(long buyerId, String orderNo, String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches("[\\x21-\\x7E]{8,64}")) {
            throw new PlatformException(TradeErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
        PayableOrder order = repository.findPayableByOrderAndBuyer(orderNo, buyerId)
                .orElseThrow(() -> new PlatformException(TradeErrorCode.ORDER_NOT_FOUND));
        if ("CLOSED".equals(order.status()) || order.expireAt().isBefore(clock.instant())) {
            throw new PlatformException(TradeErrorCode.ORDER_PAYMENT_WINDOW_CLOSED);
        }
        if ("PAID".equals(order.status()) && order.paymentIntentNo() != null) {
            return paymentClient.findByOrder(orderNo);
        }
        if (!"PENDING_PAYMENT".equals(order.status())) {
            throw new PlatformException(TradeErrorCode.ORDER_PAYMENT_STATE_INVALID);
        }
        PaymentInternalResponse response = paymentClient.create(request(order));
        if (!same(response, order)) {
            throw new IllegalStateException("Payment 返回的订单支付快照不一致");
        }
        if (!repository.attachPaymentIntent(
                orderNo,
                buyerId,
                response.intentNo(),
                clock.instant()
        )) {
            PayableOrder current = repository.findPayableByOrderAndBuyer(orderNo, buyerId).orElseThrow();
            if (!response.intentNo().equals(current.paymentIntentNo())) {
                paymentClient.close(request(order));
                throw new PlatformException(TradeErrorCode.ORDER_PAYMENT_STATE_INVALID);
            }
        }
        return response;
    }

    public PaymentInternalRequest request(PayableOrder order) {
        return new PaymentInternalRequest(
                order.orderNo(),
                order.buyerId(),
                order.sellerId(),
                order.amount(),
                order.currency(),
                order.expireAt()
        );
    }

    private boolean same(PaymentInternalResponse response, PayableOrder order) {
        return response != null
                && order.orderNo().equals(response.orderNo())
                && order.amount().compareTo(new java.math.BigDecimal(response.amount())) == 0
                && order.currency().equals(response.currency());
    }
}
