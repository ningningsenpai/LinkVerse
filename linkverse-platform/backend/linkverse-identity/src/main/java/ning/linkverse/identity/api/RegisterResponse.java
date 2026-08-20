package ning.linkverse.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * RegisterResponse 返回新用户的公开标识，不包含凭据或内部状态。
 *
 * @author ning
 * @date 2026-08-19
 */
public record RegisterResponse(
        @JsonProperty("user_id") long userId,
        String username,
        @JsonProperty("created_at") Instant createdAt
) {
}
