package io.github.ningningsenpai.linkverse.core.request;

/**
 * RequestContextKeys 集中声明请求关联字段，确保过滤器、错误处理器与日志使用同一名称。
 *
 * @author ning
 * @date 2026-08-19
 */
public final class RequestContextKeys {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = RequestContextKeys.class.getName() + ".requestId";
    public static final String REQUEST_ID_MDC_KEY = "request_id";
    public static final String TRACE_ID_MDC_KEY = "trace_id";

    private RequestContextKeys() {
    }
}
