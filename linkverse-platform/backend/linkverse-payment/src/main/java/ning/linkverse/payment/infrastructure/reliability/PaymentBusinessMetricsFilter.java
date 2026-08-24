package ning.linkverse.payment.infrastructure.reliability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ning.linkverse.payment.application.PaymentBusinessMetrics;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * PaymentBusinessMetricsFilter 按固定路由归类支付创建、回调和关单结果。
 *
 * @author ning
 * @date 2026-08-24
 */
@Component
public class PaymentBusinessMetricsFilter extends OncePerRequestFilter {

    private final PaymentBusinessMetrics metrics;

    public PaymentBusinessMetricsFilter(ObjectProvider<PaymentBusinessMetrics> metrics) {
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
        if ("POST".equals(request.getMethod()) && "/internal/v1/payment-intents".equals(path)) {
            return "payment_create";
        }
        if ("PUT".equals(request.getMethod())
                && path.matches("/internal/v1/payment-intents/[^/]+/close")) {
            return "payment_close";
        }
        if ("POST".equals(request.getMethod()) && "/api/v1/payments/callbacks/mock".equals(path)) {
            return "payment_callback";
        }
        return null;
    }
}
