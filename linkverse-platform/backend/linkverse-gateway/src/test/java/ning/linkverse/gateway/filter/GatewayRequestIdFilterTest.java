package ning.linkverse.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class GatewayRequestIdFilterTest {

    private final GatewayRequestIdFilter filter = new GatewayRequestIdFilter();

    @Test
    void shouldPreserveSafeRequestId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/").header("X-Request-Id", "request_123"));
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = current -> {
            captured.set(current);
            current.getResponse().getHeaders().add("X-Request-Id", "downstream-value");
            return current.getResponse().setComplete();
        };

        filter.filter(exchange, chain).block();

        assertThat(captured.get().getRequest().getHeaders().getFirst("X-Request-Id"))
                .isEqualTo("request_123");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id"))
                .isEqualTo("request_123");
        assertThat(exchange.getResponse().getHeaders().get("X-Request-Id"))
                .containsExactly("request_123");
    }

    @Test
    void shouldReplaceUnsafeRequestId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/").header("X-Request-Id", "包含空格 和中文"));
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        filter.filter(exchange, current -> {
            captured.set(current);
            return current.getResponse().setComplete();
        }).block();

        assertThat(captured.get().getRequest().getHeaders().getFirst("X-Request-Id"))
                .matches("[0-9a-f-]{36}");
    }
}
