package ning.linkverse.trade.application.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RecommendationInternalClientTest 验证不支持 h2c 的推荐端仍能收到完整 JSON 请求并返回模型候选。
 *
 * @author ning
 * @date 2026-09-06
 */
class RecommendationInternalClientTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldSendCompleteRequestToHttp11RecommendationServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> captured = new AtomicReference<>();
        server.createContext("/internal/v1/recommendations", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = exchange.getRequestHeaders().getFirst("Upgrade") == null ? 200 : 400;
            byte[] response = ("{\"request_id\":\"1234567890123456\",\"domain\":\"trade\","
                    + "\"model_version\":\"trade-test\",\"candidates\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            RecommendationServiceTokenProvider tokens = mock(RecommendationServiceTokenProvider.class);
            when(tokens.getAccessToken()).thenReturn("local-test-token");
            CircuitBreakerFactory<?, ?> factory = mock(CircuitBreakerFactory.class);
            CircuitBreaker breaker = mock(CircuitBreaker.class);
            when(factory.create("recommendation")).thenReturn(breaker);
            when(breaker.run(any(Supplier.class))).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
            RecommendationInternalClient client = new RecommendationInternalClient(tokens, factory,
                    "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(3), 2);
            RecommendationInternalResponse response = client.recommend(new RecommendationInternalRequest(
                    "1234567890123456", "a".repeat(64), "trade", "DETAIL", Instant.now(), 100,
                    Map.of("object_id", "12001")));
            assertThat(response.modelVersion()).isEqualTo("trade-test");
            var request = new ObjectMapper().readTree(captured.get());
            assertThat(request.path("candidate_count").asInt()).isEqualTo(100);
            assertThat(request.path("context").path("object_id").asText()).isEqualTo("12001");
        } finally {
            server.stop(0);
        }
    }
}
