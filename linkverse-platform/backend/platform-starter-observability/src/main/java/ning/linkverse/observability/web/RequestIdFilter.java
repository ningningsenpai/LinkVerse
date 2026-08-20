package ning.linkverse.observability.web;

import ning.linkverse.core.request.RequestContextKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * RequestIdFilter 校验或生成请求 ID，并在响应、请求属性和 MDC 间保持一致。
 *
 * @author ning
 * @date 2026-08-19
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class RequestIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final RequestIdProperties properties;

    public RequestIdFilter(RequestIdProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(properties.getHeaderName()));
        String previousRequestId = MDC.get(RequestContextKeys.REQUEST_ID_MDC_KEY);
        request.setAttribute(RequestContextKeys.REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(properties.getHeaderName(), requestId);
        MDC.put(RequestContextKeys.REQUEST_ID_MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previousRequestId == null) {
                MDC.remove(RequestContextKeys.REQUEST_ID_MDC_KEY);
            } else {
                MDC.put(RequestContextKeys.REQUEST_ID_MDC_KEY, previousRequestId);
            }
        }
    }

    private String resolveRequestId(String candidate) {
        if (candidate != null
                && candidate.length() <= properties.getMaxLength()
                && SAFE_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
