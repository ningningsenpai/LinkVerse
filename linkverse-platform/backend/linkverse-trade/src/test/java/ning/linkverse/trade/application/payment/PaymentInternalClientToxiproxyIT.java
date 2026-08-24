package ning.linkverse.trade.application.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.http.HttpClient;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PaymentInternalClientToxiproxyIT 验证 Payment 网络中断后不猜测结果，恢复后可继续查询。
 */
@Testcontainers(disabledWithoutDocker = false)
class PaymentInternalClientToxiproxyIT {

    @Container
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer()
            .withExtraHost("host.testcontainers.internal", "host-gateway");

    private static WireMockServer paymentStub;
    private static ToxiproxyContainer.ContainerProxy paymentProxy;
    private static PaymentInternalClient client;

    @BeforeAll
    static void setUp() {
        paymentStub = new WireMockServer(
                com.github.tomakehurst.wiremock.core.WireMockConfiguration.options()
                        .bindAddress("0.0.0.0")
                        .dynamicPort());
        paymentStub.start();
        configureFor("localhost", paymentStub.port());
        paymentProxy = TOXIPROXY.getProxy("host.testcontainers.internal", paymentStub.port());

        PaymentServiceTokenProvider tokenProvider = mock(PaymentServiceTokenProvider.class);
        when(tokenProvider.getAccessToken()).thenReturn("test-service-token");
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        );
        requestFactory.setReadTimeout(Duration.ofSeconds(2));
        client = new PaymentInternalClient(
                RestClient.builder().requestFactory(requestFactory),
                tokenProvider,
                "http://" + paymentProxy.getContainerIpAddress() + ":" + paymentProxy.getProxyPort()
        );
    }

    @AfterAll
    static void tearDown() {
        if (paymentStub != null) {
            paymentStub.stop();
        }
    }

    @Test
    void shouldRecoverAfterPaymentConnectionCutWithoutChangingClientContract() throws Exception {
        stubFor(get(urlEqualTo("/internal/v1/payment-intents/by-order/order-3"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"intent_no\":\"intent-3\",\"order_no\":\"order-3\","
                                + "\"amount\":\"68.0000\",\"currency\":\"CNY\","
                                + "\"provider\":\"mock\",\"status\":\"PENDING\","
                                + "\"expire_at\":\"2026-08-24T08:15:00Z\"}")));

        assertThat(client.findByOrder("order-3").intentNo()).isEqualTo("intent-3");

        paymentProxy.setConnectionCut(true);
        assertThatThrownBy(() -> client.findByOrder("order-3"))
                .isInstanceOf(org.springframework.web.client.RestClientException.class);

        paymentProxy.setConnectionCut(false);
        assertThat(client.findByOrder("order-3").status()).isEqualTo("PENDING");
        verify(2, getRequestedFor(urlEqualTo("/internal/v1/payment-intents/by-order/order-3")));
    }

    @Test
    void shouldInjectBoundedLatencyAsAnObservableDependencyFailure() throws Exception {
        stubFor(get(urlEqualTo("/internal/v1/payment-intents/by-order/order-4"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"intent_no\":\"intent-4\",\"order_no\":\"order-4\","
                                + "\"amount\":\"68.0000\",\"currency\":\"CNY\","
                                + "\"provider\":\"mock\",\"status\":\"PENDING\","
                                + "\"expire_at\":\"2026-08-24T08:15:00Z\"}")));

        paymentProxy.toxics().latency("payment-latency", ToxicDirection.DOWNSTREAM, 100);

        assertThat(client.findByOrder("order-4").intentNo()).isEqualTo("intent-4");
        assertThat(paymentProxy.toxics().get("payment-latency").getName())
                .isEqualTo("payment-latency");
    }
}
