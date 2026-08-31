package ning.linkverse.identity.infrastructure.security;

import ning.linkverse.identity.infrastructure.persistence.MyBatisPlusRegisteredClientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IdentitySecurityConfigurationTest 验证密码强哈希参数和服务令牌声明档案。
 *
 * @author ning
 * @date 2026-08-19
 */
class IdentitySecurityConfigurationTest {

    private final IdentitySecurityConfiguration configuration = new IdentitySecurityConfiguration();

    @Test
    void shouldUseDelegatingBcryptWithStrengthTwelve() {
        PasswordEncoder passwordEncoder = configuration.passwordEncoder();

        String encoded = passwordEncoder.encode("a-secure-password");

        assertThat(encoded).matches("^\\{bcrypt}\\$2[aby]\\$12\\$.+");
        assertThat(passwordEncoder.matches("a-secure-password", encoded)).isTrue();
        assertThat(encoded).doesNotContain("a-secure-password");
    }

    @Test
    void shouldCustomizeClientCredentialsTokenAsServiceIdentity() {
        IdentityJwtProperties properties = new IdentityJwtProperties();
        properties.setKeyId("service-test-key");
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("linkverse-trade")
                .clientSecret("{bcrypt}hash")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(IdentityTokenProfile.PAYMENT_INTERNAL_SCOPE)
                .clientSettings(ClientSettings.builder()
                        .setting(
                                MyBatisPlusRegisteredClientRepository.AUDIENCE_SETTING,
                                IdentityTokenProfile.PAYMENT_AUDIENCE
                        )
                        .build())
                .build();
        JwsHeader.Builder headers = JwsHeader.with(SignatureAlgorithm.RS256);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuedAt(Instant.parse("2026-08-19T10:15:30Z"))
                .expiresAt(Instant.parse("2026-08-19T10:20:30Z"));
        JwtEncodingContext context = JwtEncodingContext.with(headers, claims)
                .registeredClient(client)
                .authorizedScopes(Set.of(IdentityTokenProfile.PAYMENT_INTERNAL_SCOPE))
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .build();

        configuration.serviceTokenCustomizer(properties).customize(context);

        assertThat(headers.build().getHeaders())
                .containsEntry("typ", "at+jwt")
                .containsEntry("kid", "service-test-key");
        JwtClaimsSet customizedClaims = claims.build();
        assertThat(customizedClaims.getSubject()).isEqualTo("linkverse-trade");
        assertThat(customizedClaims.getAudience()).containsExactly(IdentityTokenProfile.PAYMENT_AUDIENCE);
        assertThat(customizedClaims.getId()).isNotBlank();
        assertThat(customizedClaims.getClaimAsString(IdentityTokenProfile.TOKEN_USE_CLAIM))
                .isEqualTo(IdentityTokenProfile.SERVICE_TOKEN_USE);
        assertThat(customizedClaims.getClaimAsString("client_id")).isEqualTo("linkverse-trade");
    }
}
