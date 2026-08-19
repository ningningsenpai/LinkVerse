package io.github.ningningsenpai.linkverse.identity.infrastructure.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IdentityJwtDecoderTest 使用临时密钥验证错误签发者、受众、时效和伪造签名均被拒绝。
 *
 * @author ning
 * @date 2026-08-19
 */
class IdentityJwtDecoderTest {

    private static final String ISSUER = "http://localhost:18080";

    private JwtDecoder jwtDecoder;
    private JwtEncoder trustedEncoder;

    @BeforeEach
    void setUp() throws Exception {
        RSAKey trustedKey = temporaryRsaKey();
        IdentityJwtProperties properties = new IdentityJwtProperties();
        properties.setIssuer(ISSUER);
        properties.setClockSkew(Duration.ZERO);
        jwtDecoder = new JwtKeyConfiguration().jwtDecoder(trustedKey, properties);
        trustedEncoder = encoder(trustedKey);
    }

    @Test
    void shouldRejectWrongIssuer() {
        String token = token(trustedEncoder, claims -> claims.issuer("http://malicious-issuer"));

        assertRejected(token);
    }

    @Test
    void shouldRejectWrongAudience() {
        String token = token(trustedEncoder, claims -> claims.audience(List.of("another-api")));

        assertRejected(token);
    }

    @Test
    void shouldRejectExpiredToken() {
        Instant now = Instant.now();
        String token = token(trustedEncoder, claims -> claims
                .issuedAt(now.minus(Duration.ofMinutes(5)))
                .expiresAt(now.minus(Duration.ofMinutes(1))));

        assertRejected(token);
    }

    @Test
    void shouldRejectTokenSignedByUntrustedPrivateKey() throws Exception {
        JwtEncoder untrustedEncoder = encoder(temporaryRsaKey());
        String token = token(untrustedEncoder, claims -> {
        });

        assertRejected(token);
    }

    private String token(JwtEncoder encoder, Consumer<JwtClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("42")
                .audience(List.of(IdentityTokenProfile.USER_AUDIENCE))
                .issuedAt(now.minusSeconds(5))
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .id(UUID.randomUUID().toString())
                .claim(IdentityTokenProfile.TOKEN_USE_CLAIM, IdentityTokenProfile.USER_TOKEN_USE)
                .claim("scope", IdentityTokenProfile.USER_SCOPE);
        customizer.accept(claims);
        JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256)
                .type("at+jwt")
                .keyId("test-key")
                .build();
        return encoder.encode(JwtEncoderParameters.from(headers, claims.build())).getTokenValue();
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private JwtEncoder encoder(RSAKey rsaKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(rsaKey)));
    }

    private RSAKey temporaryRsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("test-key")
                .build();
    }
}
