package ning.linkverse.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * LoginResponse 按 Bearer 令牌响应格式返回短期用户访问令牌。
 *
 * @author ning
 * @date 2026-08-19
 */
public record LoginResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn
) {
}
