package ning.linkverse.payment.api;

import ning.linkverse.payment.application.PaymentApplicationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PaymentIntentController 只允许支付所属用户查询 Intent。
 *
 * @author ning
 * @date 2026-08-24
 */
@RestController
@RequestMapping("/api/v1/payment-intents")
public class PaymentIntentController {

    private final PaymentApplicationService service;

    public PaymentIntentController(PaymentApplicationService service) {
        this.service = service;
    }

    @GetMapping("/{intentNo}")
    public PaymentIntentResponse find(@AuthenticationPrincipal Jwt jwt, @PathVariable String intentNo) {
        return PaymentIntentResponse.from(service.findOwned(intentNo, Long.parseLong(jwt.getSubject())));
    }
}
