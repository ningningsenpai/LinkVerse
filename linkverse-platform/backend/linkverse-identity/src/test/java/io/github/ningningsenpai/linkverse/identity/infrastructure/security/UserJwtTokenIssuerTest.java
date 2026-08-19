package io.github.ningningsenpai.linkverse.identity.infrastructure.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.ningningsenpai.linkverse.identity.application.AuthenticatedUser;
import io.github.ningningsenpai.linkverse.identity.application.IssuedAccessToken;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserJwtTokenIssuerTest 使用临时 RSA 密钥验证用户令牌签名和完整声明档案。
 *
 * @author ning
 * @date 2026-08-19
 */
class UserJwtTokenIssuerTest {

    @Test
    void shouldIssueSignedRs256UserAccessToken() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        KeyPair keyPair = temporaryRsaKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID("test-key")
                .build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableJWKSet<SecurityContext>(new JWKSet(rsaKey))
        );
        IdentityJwtProperties properties = new IdentityJwtProperties();
        properties.setIssuer("http://localhost:18080");
        properties.setKeyId("test-key");
        properties.setUserAccessTokenTtl(Duration.ofMinutes(15));
        properties.setClockSkew(Duration.ofSeconds(30));
        UserJwtTokenIssuer issuer = new UserJwtTokenIssuer(
                encoder,
                properties,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        IssuedAccessToken issuedToken = issuer.issue(new AuthenticatedUser(42L, "Alice"));

        Jwt jwt = new JwtKeyConfiguration()
                .jwtDecoder(rsaKey, properties)
                .decode(issuedToken.tokenValue());
        assertThat(issuedToken.expiresInSeconds()).isEqualTo(900);
        assertThat(jwt.getHeaders())
                .containsEntry("alg", "RS256")
                .containsEntry("typ", "at+jwt")
                .containsEntry("kid", "test-key");
        assertThat(jwt.getIssuer().toString()).isEqualTo("http://localhost:18080");
        assertThat(jwt.getAudience()).containsExactly(IdentityTokenProfile.USER_AUDIENCE);
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getIssuedAt()).isEqualTo(now);
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(15)));
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getClaimAsString(IdentityTokenProfile.TOKEN_USE_CLAIM))
                .isEqualTo(IdentityTokenProfile.USER_TOKEN_USE);
        assertThat(jwt.getClaimAsString("scope")).isEqualTo(IdentityTokenProfile.USER_SCOPE);
    }

    private KeyPair temporaryRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
