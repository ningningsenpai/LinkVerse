package io.github.ningningsenpai.linkverse.security.jwt;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * LinkVerseJwtProfileValidator 校验用户令牌和服务令牌必须满足的业务档案声明。
 *
 * @author ning
 * @date 2026-08-19
 */
public final class LinkVerseJwtProfileValidator implements OAuth2TokenValidator<Jwt> {

    private final String expectedAudience;
    private final String tokenUse;
    private final String requiredSubject;
    private final String requiredClientId;
    private final String requiredScope;

    public LinkVerseJwtProfileValidator(
            String expectedAudience,
            String tokenUse,
            String requiredSubject,
            String requiredClientId,
            String requiredScope
    ) {
        this.expectedAudience = normalize(expectedAudience);
        this.tokenUse = normalize(tokenUse);
        this.requiredSubject = normalize(requiredSubject);
        this.requiredClientId = normalize(requiredClientId);
        this.requiredScope = normalize(requiredScope);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Objects.requireNonNull(jwt, "JWT 不能为空");
        if (!"at+jwt".equals(jwt.getHeaders().get("typ"))) {
            return failure("令牌类型必须为 at+jwt");
        }
        Map<String, Object> claims = jwt.getClaims();
        String jwtId = stringClaim(claims, "jti");
        String subject = stringClaim(claims, "sub");
        if (isBlank(jwtId)) {
            return failure("令牌缺少 jti 声明");
        }
        if (isBlank(subject)) {
            return failure("令牌缺少 sub 声明");
        }
        if (expectedAudience != null && !containsAudience(claims.get("aud"), expectedAudience)) {
            return failure("令牌受众不匹配");
        }
        if (requiredSubject != null && !requiredSubject.equals(subject)) {
            return failure("令牌主体不匹配");
        }
        if (tokenUse != null && !tokenUse.equals(stringClaim(claims, "token_use"))) {
            return failure("令牌用途不匹配");
        }
        if (requiredClientId != null && !requiredClientId.equals(stringClaim(claims, "client_id"))) {
            return failure("令牌客户端不匹配");
        }
        if (requiredScope != null && !JwtScopeClaims.parse(jwt).contains(requiredScope)) {
            return failure("令牌权限范围不足");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }

    private static String normalize(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String stringClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        return value instanceof String text ? text : null;
    }

    private static boolean containsAudience(Object audienceClaim, String expectedAudience) {
        if (audienceClaim instanceof String audience) {
            return expectedAudience.equals(audience);
        }
        if (audienceClaim instanceof Collection<?> audiences) {
            return audiences.stream().anyMatch(expectedAudience::equals);
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
