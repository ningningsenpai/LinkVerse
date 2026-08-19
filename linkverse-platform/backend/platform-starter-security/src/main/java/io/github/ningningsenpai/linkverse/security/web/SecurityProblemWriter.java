package io.github.ningningsenpai.linkverse.security.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ningningsenpai.linkverse.core.request.RequestContextKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SecurityProblemWriter 负责在 MVC 异常处理器接管前写出一致的安全错误协议。
 *
 * @author ning
 * @date 2026-08-19
 */
final class SecurityProblemWriter {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern TRACE_PARENT = Pattern.compile(
            "^[0-9a-fA-F]{2}-([0-9a-fA-F]{32})-[0-9a-fA-F]{16}-[0-9a-fA-F]{2}(?:-.*)?$"
    );

    private final ObjectMapper objectMapper;

    SecurityProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper 不能为空");
    }

    void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String title,
            String detail
    ) throws IOException {
        String requestId = requestId(request);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(RequestContextKeys.REQUEST_ID_HEADER, requestId);

        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "urn:linkverse:error:" + code.toLowerCase(Locale.ROOT));
        problem.put("title", title);
        problem.put("status", status);
        problem.put("code", code);
        problem.put("detail", detail);
        problem.put("request_id", requestId);
        problem.put("trace_id", traceId(request));
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestContextKeys.REQUEST_ID_ATTRIBUTE);
        if (attribute instanceof String value && SAFE_REQUEST_ID.matcher(value).matches()) {
            return value;
        }
        String header = request.getHeader(RequestContextKeys.REQUEST_ID_HEADER);
        if (header != null && SAFE_REQUEST_ID.matcher(header).matches()) {
            return header;
        }
        return UUID.randomUUID().toString();
    }

    private String traceId(HttpServletRequest request) {
        String traceId = MDC.get(RequestContextKeys.TRACE_ID_MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = MDC.get("traceId");
        }
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        String traceParent = request.getHeader("traceparent");
        if (traceParent == null) {
            return null;
        }
        Matcher matcher = TRACE_PARENT.matcher(traceParent);
        return matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }
}
