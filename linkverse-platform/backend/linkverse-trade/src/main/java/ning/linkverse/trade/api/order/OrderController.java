package ning.linkverse.trade.api.order;

import jakarta.validation.Valid;
import ning.linkverse.trade.application.order.OrderApplicationService;
import ning.linkverse.trade.application.order.OrderCreationResult;
import ning.linkverse.trade.application.payment.PaymentInternalResponse;
import ning.linkverse.trade.application.payment.PaymentOrchestrationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OrderController 暴露幂等立即购买和订单所有者查询接口。
 *
 * @author ning
 * @date 2026-08-24
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderApplicationService orderApplicationService;
    private final PaymentOrchestrationService paymentOrchestrationService;

    public OrderController(
            OrderApplicationService orderApplicationService,
            PaymentOrchestrationService paymentOrchestrationService
    ) {
        this.orderApplicationService = orderApplicationService;
        this.paymentOrchestrationService = paymentOrchestrationService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        OrderCreationResult result = orderApplicationService.create(
                userId(jwt),
                request.listingId(),
                request.quantity(),
                idempotencyKey
        );
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(OrderResponse.from(result.order(), false));
    }

    @GetMapping("/{orderNo}")
    public OrderResponse find(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderNo) {
        return OrderResponse.from(orderApplicationService.findOwned(orderNo, userId(jwt)), true);
    }

    @PutMapping("/{orderNo}/payment-intent")
    public PaymentInternalResponse createPayment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String orderNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return paymentOrchestrationService.create(userId(jwt), orderNo, idempotencyKey);
    }

    private long userId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("用户令牌中的主体不是有效用户编号", exception);
        }
    }
}
