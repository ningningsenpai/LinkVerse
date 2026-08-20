package ning.linkverse.identity.infrastructure.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2ErrorAuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * OAuth2TokenErrorResponseHandler 保留标准 OAuth 错误 JSON，并返回安全的中文错误说明。
 *
 * @author ning
 * @date 2026-08-19
 */
@Component
public class OAuth2TokenErrorResponseHandler implements AuthenticationFailureHandler {

    private static final Map<String, String> SAFE_DESCRIPTIONS = Map.ofEntries(
            Map.entry(OAuth2ErrorCodes.INVALID_REQUEST, "令牌请求参数无效"),
            Map.entry(OAuth2ErrorCodes.INVALID_CLIENT, "客户端认证失败"),
            Map.entry(OAuth2ErrorCodes.INVALID_GRANT, "授权凭据无效"),
            Map.entry(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT, "客户端无权使用该授权类型"),
            Map.entry(OAuth2ErrorCodes.UNSUPPORTED_GRANT_TYPE, "不支持该授权类型"),
            Map.entry(OAuth2ErrorCodes.INVALID_SCOPE, "请求的权限范围无效"),
            Map.entry(OAuth2ErrorCodes.ACCESS_DENIED, "访问被拒绝"),
            Map.entry(OAuth2ErrorCodes.SERVER_ERROR, "令牌服务处理失败"),
            Map.entry(OAuth2ErrorCodes.TEMPORARILY_UNAVAILABLE, "令牌服务暂时不可用")
    );

    private final OAuth2ErrorAuthenticationFailureHandler delegate =
            new OAuth2ErrorAuthenticationFailureHandler();

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        OAuth2Error sourceError = exception instanceof OAuth2AuthenticationException oauthException
                ? oauthException.getError()
                : new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR);
        String description = SAFE_DESCRIPTIONS.getOrDefault(
                sourceError.getErrorCode(),
                "OAuth 令牌请求失败"
        );
        OAuth2Error safeError = new OAuth2Error(
                sourceError.getErrorCode(),
                description,
                null
        );
        delegate.onAuthenticationFailure(
                request,
                response,
                new OAuth2AuthenticationException(safeError, exception)
        );
    }
}
