package io.github.ningningsenpai.linkverse.observability.web;

import io.github.ningningsenpai.linkverse.core.request.RequestContextKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RequestIdFilterTest 验证请求 ID 的传播、输入约束与 MDC 清理。
 *
 * @author ning
 * @date 2026-08-19
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter(new RequestIdProperties());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldPropagateValidRequestIdAndClearMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestContextKeys.REQUEST_ID_HEADER, "request-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (currentRequest, currentResponse) -> {
            assertThat(currentRequest.getAttribute(RequestContextKeys.REQUEST_ID_ATTRIBUTE))
                    .isEqualTo("request-1");
            assertThat(MDC.get(RequestContextKeys.REQUEST_ID_MDC_KEY)).isEqualTo("request-1");
        });

        assertThat(response.getHeader(RequestContextKeys.REQUEST_ID_HEADER)).isEqualTo("request-1");
        assertThat(MDC.get(RequestContextKeys.REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    void shouldReplaceUnsafeRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestContextKeys.REQUEST_ID_HEADER, "unsafe\r\nforged-header");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (currentRequest, currentResponse) -> {
        });

        assertThat(response.getHeader(RequestContextKeys.REQUEST_ID_HEADER))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void shouldReplaceRequestIdContainingColon() throws Exception {
        assertGeneratedRequestId("request:1");
    }

    @Test
    void shouldGenerateRequestIdWhenHeaderIsMissing() throws Exception {
        assertGeneratedRequestId(null);
    }

    @Test
    void shouldReplaceEmptyRequestId() throws Exception {
        assertGeneratedRequestId("");
    }

    @Test
    void shouldReplaceRequestIdLongerThan64Characters() throws Exception {
        assertGeneratedRequestId("a".repeat(65));
    }

    private void assertGeneratedRequestId(String candidate) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (candidate != null) {
            request.addHeader(RequestContextKeys.REQUEST_ID_HEADER, candidate);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (currentRequest, currentResponse) -> {
        });

        assertThat(response.getHeader(RequestContextKeys.REQUEST_ID_HEADER))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
