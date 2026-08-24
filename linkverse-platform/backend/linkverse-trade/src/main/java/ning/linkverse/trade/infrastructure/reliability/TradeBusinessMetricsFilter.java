package ning.linkverse.trade.infrastructure.reliability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ning.linkverse.trade.application.TradeBusinessMetrics;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * TradeBusinessMetricsFilter 仅将创建类接口映射为固定操作标签。
 *
 * @author ning
 * @date 2026-08-24
 */
@Component
public class TradeBusinessMetricsFilter extends OncePerRequestFilter {

    private final TradeBusinessMetrics metrics;

    public TradeBusinessMetricsFilter(ObjectProvider<TradeBusinessMetrics> metrics) {
        this.metrics = metrics.getIfAvailable();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String operation = operation(request);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (operation != null && metrics != null) {
                metrics.record(operation, response.getStatus() < 400 ? "success" : "failure");
            }
        }
    }

    private String operation(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("POST".equals(request.getMethod()) && "/api/v1/orders".equals(path)) {
            return "order_create";
        }
        if ("PUT".equals(request.getMethod()) && path.matches("/api/v1/orders/[^/]+/payment-intent")) {
            return "payment_orchestrate";
        }
        if ("POST".equals(request.getMethod())
                && path.matches("/api/v1/seckill/campaigns/[^/]+/reservations")) {
            return "seckill_reserve";
        }
        return null;
    }
}
