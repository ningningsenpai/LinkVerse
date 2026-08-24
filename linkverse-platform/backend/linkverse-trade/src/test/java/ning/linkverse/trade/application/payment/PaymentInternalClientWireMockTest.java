package ning.linkverse.trade.application.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PaymentInternalClientWireMockTest 验证 Trade 到 Payment 的 HTTP 契约和有限恢复边界。
 */
class PaymentInternalClientWireMockTest {

    private static final String TOKEN = "test-service-token";
    private WireMockServer paymentStub;
    private PaymentInternalClient client;

    @BeforeEach
    void setUp() {
        paymentStub = new WireMockServer(0);
        paymentStub.start();
        configureFor("localhost", paymentStub.port());
        PaymentServiceTokenProvider tokenProvider = mock(PaymentServiceTokenProvider.class);
        when(tokenProvider.getAccessToken()).thenReturn(TOKEN);
        client = new PaymentInternalClient(
                RestClient.builder(),
                tokenProvider,
                paymentStub.baseUrl()
        );
    }

    @AfterEach
    void tearDown() {
        paymentStub.stop();
    }

    @Test
    void shouldSendServiceTokenAndIdempotencyKeyToPayment() {
        stubFor(post(urlEqualTo("/internal/v1/payment-intents"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("Idempotency-Key", equalTo("order-1"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(paymentResponse("intent-1", "PENDING"))));

        PaymentInternalResponse response = client.create(request("order-1"));

        assertThat(response.intentNo()).isEqualTo("intent-1");
        assertThat(response.status()).isEqualTo("PENDING");
        verify(1, postRequestedFor(urlEqualTo("/internal/v1/payment-intents")));
    }

    @Test
    void shouldExposeProviderFailureAndRecoverOnBoundedCallerRetry() {
        stubFor(get(urlEqualTo("/internal/v1/payment-intents/by-order/order-2"))
                .inScenario("payment-recovery")
                .whenScenarioStateIs("Started")
                .atPriority(1)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("Payment recovered"));
        stubFor(get(urlEqualTo("/internal/v1/payment-intents/by-order/order-2"))
                .inScenario("payment-recovery")
                .whenScenarioStateIs("Payment recovered")
                .atPriority(2)
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(paymentResponse("intent-2", "PENDING"))));

        assertThatThrownBy(() -> client.findByOrder("order-2"))
                .hasMessageContaining("500");
        PaymentInternalResponse recovered = client.findByOrder("order-2");

        assertThat(recovered.intentNo()).isEqualTo("intent-2");
        assertThat(recovered.status()).isEqualTo("PENDING");
        verify(2, getRequestedFor(urlEqualTo("/internal/v1/payment-intents/by-order/order-2")));
    }

    private PaymentInternalRequest request(String orderNo) {
        return new PaymentInternalRequest(
                orderNo,
                10001L,
                90001L,
                new BigDecimal("68.0000"),
                "CNY",
                Instant.parse("2026-08-24T08:15:00Z")
        );
    }

    private String paymentResponse(String intentNo, String status) {
        return "{\"intent_no\":\"" + intentNo + "\","
                + "\"order_no\":\"order-" + intentNo.substring(intentNo.length() - 1) + "\","
                + "\"amount\":\"68.0000\",\"currency\":\"CNY\","
                + "\"provider\":\"mock\",\"status\":\"" + status + "\","
                + "\"expire_at\":\"2026-08-24T08:15:00Z\"}";
    }
}
