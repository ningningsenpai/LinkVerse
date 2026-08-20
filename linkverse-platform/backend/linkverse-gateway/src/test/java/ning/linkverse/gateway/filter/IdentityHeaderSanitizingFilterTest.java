package ning.linkverse.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class IdentityHeaderSanitizingFilterTest {

    @Test
    void shouldRemoveUntrustedIdentityHeadersAndKeepOtherHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/")
                .header("X-User-Id", "伪造用户")
                .header("X-Client-Id", "伪造客户端")
                .header("X-Request-Id", "request-1"));
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        new IdentityHeaderSanitizingFilter().filter(exchange, current -> {
            captured.set(current);
            return Mono.empty();
        }).block();

        assertThat(captured.get().getRequest().getHeaders().containsKey("X-User-Id")).isFalse();
        assertThat(captured.get().getRequest().getHeaders().containsKey("X-Client-Id")).isFalse();
        assertThat(captured.get().getRequest().getHeaders().getFirst("X-Request-Id"))
                .isEqualTo("request-1");
    }
}
