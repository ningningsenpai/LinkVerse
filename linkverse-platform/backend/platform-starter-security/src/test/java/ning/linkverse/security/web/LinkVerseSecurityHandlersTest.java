package ning.linkverse.security.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LinkVerseSecurityHandlersTest 验证认证与授权失败使用统一中文 Problem Details。
 *
 * @author ning
 * @date 2026-08-19
 */
class LinkVerseSecurityHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldWriteUnauthorizedProblemWithoutExceptionDetails() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new LinkVerseAuthenticationEntryPoint(objectMapper).commence(
                request,
                response,
                new BadCredentialsException("raw-token-secret")
        );

        Map<String, Object> body = body(response);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(body)
                .containsEntry("code", "SECURITY_UNAUTHORIZED")
                .containsEntry("detail", "请提供有效的访问令牌")
                .containsEntry("request_id", "request-1")
                .containsEntry("trace_id", "0123456789abcdef0123456789abcdef");
        assertThat(response.getContentAsString()).doesNotContain("raw-token-secret");
    }

    @Test
    void shouldWriteForbiddenProblem() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new LinkVerseAccessDeniedHandler(objectMapper).handle(
                request,
                response,
                new AccessDeniedException("internal-policy")
        );

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body(response))
                .containsEntry("code", "SECURITY_FORBIDDEN")
                .containsEntry("detail", "当前身份无权访问该资源");
        assertThat(response.getContentAsString()).doesNotContain("internal-policy");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "request-1");
        request.addHeader("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
        return request;
    }

    private Map<String, Object> body(MockHttpServletResponse response) throws Exception {
        return objectMapper.readValue(response.getContentAsByteArray(), new TypeReference<>() {
        });
    }
}
