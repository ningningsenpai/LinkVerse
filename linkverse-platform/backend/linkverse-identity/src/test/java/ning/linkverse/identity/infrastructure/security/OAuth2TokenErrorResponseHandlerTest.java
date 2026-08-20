package ning.linkverse.identity.infrastructure.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OAuth2TokenErrorResponseHandlerTest 验证令牌失败响应保持标准 OAuth JSON 且不泄漏英文内部描述。
 *
 * @author ning
 * @date 2026-08-19
 */
class OAuth2TokenErrorResponseHandlerTest {

    private final OAuth2TokenErrorResponseHandler handler = new OAuth2TokenErrorResponseHandler();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnChineseStandardOauthError() throws Exception {
        OAuth2Error sourceError = new OAuth2Error(
                OAuth2ErrorCodes.INVALID_SCOPE,
                "English framework detail containing internal data",
                null
        );
        MockHttpServletResponse response = handle(new OAuth2AuthenticationException(sourceError));

        Map<String, Object> body = body(response);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(body)
                .containsEntry("error", OAuth2ErrorCodes.INVALID_SCOPE)
                .containsEntry("error_description", "请求的权限范围无效")
                .doesNotContainKeys("status", "title", "detail", "code");
        assertThat(response.getContentAsString()).doesNotContain("English", "internal data");
    }

    @Test
    void shouldTranslateInvalidClientWithoutLeakingCredentials() throws Exception {
        OAuth2Error sourceError = new OAuth2Error(
                OAuth2ErrorCodes.INVALID_CLIENT,
                "client_secret=raw-secret",
                null
        );
        MockHttpServletResponse response = handle(new OAuth2AuthenticationException(sourceError));

        assertThat(body(response))
                .containsEntry("error", OAuth2ErrorCodes.INVALID_CLIENT)
                .containsEntry("error_description", "客户端认证失败");
        assertThat(response.getContentAsString()).doesNotContain("raw-secret", "client_secret");
    }

    @Test
    void shouldConvertUnexpectedAuthenticationFailureToSafeServerError() throws Exception {
        MockHttpServletResponse response = handle(new BadCredentialsException("sensitive failure"));

        assertThat(body(response))
                .containsEntry("error", OAuth2ErrorCodes.SERVER_ERROR)
                .containsEntry("error_description", "令牌服务处理失败");
        assertThat(response.getContentAsString()).doesNotContain("sensitive failure");
    }

    private MockHttpServletResponse handle(AuthenticationException exception) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(
                new MockHttpServletRequest("POST", "/oauth2/token"),
                response,
                exception
        );
        return response;
    }

    private Map<String, Object> body(MockHttpServletResponse response) throws Exception {
        return objectMapper.readValue(response.getContentAsByteArray(), new TypeReference<>() {
        });
    }
}
