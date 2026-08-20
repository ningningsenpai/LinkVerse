package ning.linkverse.security.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LinkVerseJwtProfileValidatorTest 覆盖服务令牌档案的成功与拒绝路径。
 *
 * @author ning
 * @date 2026-08-19
 */
class LinkVerseJwtProfileValidatorTest {

    private final LinkVerseJwtProfileValidator validator = new LinkVerseJwtProfileValidator(
            "linkverse-payment",
            "service",
            "linkverse-trade",
            "trade-client",
            "payment:write"
    );

    @Test
    void shouldAcceptMatchingServiceToken() {
        OAuth2TokenValidatorResult result = validator.validate(jwt("payment:read payment:write"));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void shouldRejectMissingScopeWithChineseInvalidTokenError() {
        OAuth2TokenValidatorResult result = validator.validate(jwt("payment:read"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.getErrorCode()).isEqualTo("invalid_token");
            assertThat(error.getDescription()).isEqualTo("令牌权限范围不足");
        });
    }

    @Test
    void shouldRejectMissingJwtId() {
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "none")
                .header("typ", "at+jwt")
                .subject("linkverse-trade")
                .audience(List.of("linkverse-payment"))
                .claim("token_use", "service")
                .claim("client_id", "trade-client")
                .claim("scope", "payment:write")
                .build();

        OAuth2TokenValidatorResult result = validator.validate(token);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
                .extracting(error -> error.getDescription())
                .isEqualTo("令牌缺少 jti 声明");
    }

    @Test
    void shouldRejectMissingAudienceWithoutThrowing() {
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "none")
                .header("typ", "at+jwt")
                .subject("linkverse-trade")
                .claim("jti", "jwt-1")
                .claim("token_use", "service")
                .claim("client_id", "trade-client")
                .claim("scope", "payment:write")
                .build();

        OAuth2TokenValidatorResult result = validator.validate(token);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
                .extracting(error -> error.getDescription())
                .isEqualTo("令牌受众不匹配");
    }

    @Test
    void shouldAlwaysRejectMissingSubject() {
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "none")
                .header("typ", "at+jwt")
                .audience(List.of("linkverse-payment"))
                .claim("jti", "jwt-1")
                .claim("token_use", "service")
                .claim("client_id", "trade-client")
                .claim("scope", "payment:write")
                .build();

        LinkVerseJwtProfileValidator structuralValidator = new LinkVerseJwtProfileValidator(
                null,
                null,
                null,
                null,
                null
        );

        OAuth2TokenValidatorResult result = structuralValidator.validate(token);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
                .extracting(error -> error.getDescription())
                .isEqualTo("令牌缺少 sub 声明");
    }

    @Test
    void shouldRejectUnexpectedJwtType() {
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "none")
                .header("typ", "JWT")
                .subject("linkverse-trade")
                .audience(List.of("linkverse-payment"))
                .claim("jti", "jwt-1")
                .claim("token_use", "service")
                .claim("client_id", "trade-client")
                .claim("scope", "payment:write")
                .build();

        OAuth2TokenValidatorResult result = validator.validate(token);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.getErrorCode()).isEqualTo("invalid_token");
            assertThat(error.getDescription()).isEqualTo("令牌类型必须为 at+jwt");
        });
    }

    private Jwt jwt(String scope) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .header("typ", "at+jwt")
                .subject("linkverse-trade")
                .audience(List.of("linkverse-payment"))
                .claim("jti", "jwt-1")
                .claim("token_use", "service")
                .claim("client_id", "trade-client")
                .claim("scope", scope)
                .build();
    }
}
