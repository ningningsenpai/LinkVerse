package ning.linkverse.trade.application.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * PaymentInternalClient 使用服务 JWT 调用 Payment，绝不访问 Payment Schema。
 *
 * @author ning
 * @date 2026-08-24
 */
@Component
public class PaymentInternalClient {

    private final RestClient restClient;
    private final PaymentServiceTokenProvider tokenProvider;

    public PaymentInternalClient(
            @LoadBalanced RestClient.Builder builder,
            PaymentServiceTokenProvider tokenProvider,
            @Value("${linkverse.trade.payment-base-url:http://linkverse-payment}") String baseUrl
    ) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.tokenProvider = tokenProvider;
    }

    public PaymentInternalResponse create(PaymentInternalRequest request) {
        return restClient.post()
                .uri("/internal/v1/payment-intents")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .header("Idempotency-Key", request.orderNo())
                .body(request)
                .retrieve()
                .body(PaymentInternalResponse.class);
    }

    public PaymentInternalResponse findByOrder(String orderNo) {
        return restClient.get()
                .uri("/internal/v1/payment-intents/by-order/{orderNo}", orderNo)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve()
                .body(PaymentInternalResponse.class);
    }

    public PaymentInternalResponse close(PaymentInternalRequest request) {
        return restClient.put()
                .uri("/internal/v1/payment-intents/{orderNo}/close", request.orderNo())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .body(request)
                .retrieve()
                .body(PaymentInternalResponse.class);
    }

    private String bearer() {
        return "Bearer " + tokenProvider.getAccessToken();
    }
}
