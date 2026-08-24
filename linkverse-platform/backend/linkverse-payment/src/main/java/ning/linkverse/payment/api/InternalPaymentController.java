package ning.linkverse.payment.api;

import jakarta.validation.Valid;
import ning.linkverse.core.error.CommonErrorCode;
import ning.linkverse.core.error.PlatformException;
import ning.linkverse.payment.application.PaymentApplicationService;
import ning.linkverse.payment.domain.CreatePaymentIntent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * InternalPaymentController 只接受经过服务 JWT 校验的 Trade 调用。
 *
 * @author ning
 * @date 2026-08-24
 */
@RestController
@RequestMapping("/internal/v1/payment-intents")
public class InternalPaymentController {

    private final PaymentApplicationService service;

    public InternalPaymentController(PaymentApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public PaymentIntentResponse create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentIntentRequest request
    ) {
        requireOrderKey(idempotencyKey, request.orderNo());
        return PaymentIntentResponse.from(service.create(command(request)));
    }

    @GetMapping("/by-order/{orderNo}")
    public PaymentIntentResponse findByOrder(@PathVariable String orderNo) {
        return PaymentIntentResponse.from(service.findByOrder(orderNo));
    }

    @PutMapping("/{orderNo}/close")
    public PaymentIntentResponse close(
            @PathVariable String orderNo,
            @Valid @RequestBody CreatePaymentIntentRequest request
    ) {
        requireOrderKey(orderNo, request.orderNo());
        return PaymentIntentResponse.from(service.close(command(request)));
    }

    private CreatePaymentIntent command(CreatePaymentIntentRequest request) {
        return new CreatePaymentIntent(
                request.orderNo(),
                request.buyerId(),
                request.merchantId(),
                request.amount(),
                request.currency(),
                request.expireAt()
        );
    }

    private void requireOrderKey(String actual, String orderNo) {
        if (!orderNo.equals(actual)) {
            throw new PlatformException(CommonErrorCode.INVALID_REQUEST);
        }
    }
}
