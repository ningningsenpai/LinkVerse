package ning.linkverse.web.error;

import ning.linkverse.core.error.CommonErrorCode;
import ning.linkverse.core.error.PlatformException;
import ning.linkverse.core.request.RequestContextKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlatformProblemExceptionHandlerTest 验证错误响应的稳定字段和敏感信息隔离。
 *
 * @author ning
 * @date 2026-08-19
 */
class PlatformProblemExceptionHandlerTest {

    private final PlatformProblemExceptionHandler handler = new PlatformProblemExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldReturnStableProblemDetails() {
        MockHttpServletRequest request = requestWithContext();

        ResponseEntity<ProblemDetail> response = handler.handlePlatformException(
                new PlatformException(CommonErrorCode.CONFLICT, "当前状态不允许重复操作"),
                request
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("当前状态不允许重复操作");
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "COMMON_CONFLICT")
                .containsEntry("request_id", "request-1")
                .containsEntry("trace_id", "0123456789abcdef0123456789abcdef");
    }

    @Test
    void shouldHideUnexpectedExceptionMessage() {
        MockHttpServletRequest request = requestWithContext();

        ResponseEntity<ProblemDetail> response = handler.handleUnexpectedException(
                new IllegalStateException("jdbc:mysql://private-host/secret?password=raw"),
                request
        );

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("系统暂时无法处理请求");
        assertThat(response.getBody().getDetail()).doesNotContain("private-host", "password");
    }

    private MockHttpServletRequest requestWithContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestContextKeys.REQUEST_ID_ATTRIBUTE, "request-1");
        MDC.put(RequestContextKeys.TRACE_ID_MDC_KEY, "0123456789abcdef0123456789abcdef");
        return request;
    }
}
