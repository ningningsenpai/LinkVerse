package ning.linkverse.gateway.filter;

import java.util.ArrayList;
import java.util.Locale;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * IdentityHeaderSanitizingFilter 在路由前移除客户端可伪造的身份派生请求头。
 *
 * @author ning
 * @date 2026-08-19
 */
@Component
public final class IdentityHeaderSanitizingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> new ArrayList<>(headers.keySet()).stream()
                        .filter(this::isUntrustedIdentityHeader)
                        .forEach(headers::remove))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private boolean isUntrustedIdentityHeader(String headerName) {
        String normalizedName = headerName.toLowerCase(Locale.ROOT);
        return normalizedName.startsWith("x-user-") || normalizedName.startsWith("x-client-");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
