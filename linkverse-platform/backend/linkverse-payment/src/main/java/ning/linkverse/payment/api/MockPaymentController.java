package ning.linkverse.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.payment.application.MockHmacVerifier;
import ning.linkverse.payment.application.MockPaymentCallbackService;
import ning.linkverse.payment.application.PaymentApplicationService;
import ning.linkverse.payment.domain.PaymentCallback;
import ning.linkverse.payment.domain.PaymentIntent;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/**
 * MockPaymentController 仅在 local/test 提供可复现的模拟确认与回调入口。
 *
 * @author ning
 * @date 2026-08-24
 */
@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1")
public class MockPaymentController {

    private final PaymentApplicationService paymentService;
    private final MockPaymentCallbackService callbackService;
    private final MockHmacVerifier verifier;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MockPaymentController(
            PaymentApplicationService paymentService,
            MockPaymentCallbackService callbackService,
            MockHmacVerifier verifier,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.paymentService = paymentService;
        this.callbackService = callbackService;
        this.verifier = verifier;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @PostMapping("/mock-provider/payment-intents/{intentNo}/confirm")
    public PaymentIntentResponse confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String intentNo,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        PaymentIntent intent = paymentService.findOwned(intentNo, Long.parseLong(jwt.getSubject()));
        String stable = digest(idempotencyKey + "|" + intentNo).substring(0, 32);
        PaymentCallback callback = new PaymentCallback(
                "n_" + stable,
                "t_" + stable,
                intent.intentNo(),
                intent.orderNo(),
                intent.merchantId(),
                intent.amount(),
                intent.currency(),
                "SUCCEEDED"
        );
        byte[] rawBody;
        try {
            rawBody = objectMapper.writeValueAsBytes(callback);
        } catch (Exception exception) {
            throw new IllegalStateException("生成 Mock Provider 回调失败", exception);
        }
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        return PaymentIntentResponse.from(callbackService.accept(
                timestamp,
                verifier.sign(timestamp, rawBody),
                rawBody
        ));
    }

    @PostMapping("/payments/callbacks/mock")
    public PaymentIntentResponse callback(
            @RequestHeader("X-Mock-Timestamp") String timestamp,
            @RequestHeader("X-Mock-Signature") String signature,
            @RequestBody byte[] rawBody
    ) {
        return PaymentIntentResponse.from(callbackService.accept(timestamp, signature, rawBody));
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("运行环境不支持SHA-256", exception);
        }
    }
}
