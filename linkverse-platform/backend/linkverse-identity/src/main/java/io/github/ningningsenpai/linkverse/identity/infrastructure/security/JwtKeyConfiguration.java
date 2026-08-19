package io.github.ningningsenpai.linkverse.identity.infrastructure.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.ningningsenpai.linkverse.security.jwt.LinkVerseJwtProfileValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;

/**
 * JwtKeyConfiguration 从外部资源加载 RSA 密钥，并只通过 JWK 端点公开公钥部分。
 *
 * @author ning
 * @date 2026-08-19
 */
@Configuration(proxyBeanMethods = false)
public class JwtKeyConfiguration {

    @Bean
    RSAKey identityRsaKey(IdentityJwtProperties properties, ResourceLoader resourceLoader) {
        requireText(properties.getIssuer(), "JWT 签发者未配置");
        requireText(properties.getKeyId(), "JWT 密钥标识未配置");
        requireText(properties.getPrivateKey(), "JWT 私钥资源未配置");
        requireText(properties.getPublicKey(), "JWT 公钥资源未配置");
        requirePositiveDuration(properties.getUserAccessTokenTtl(), "用户访问令牌有效期必须大于零");
        requirePositiveDuration(properties.getServiceAccessTokenTtl(), "服务访问令牌有效期必须大于零");
        if (properties.getClockSkew() == null || properties.getClockSkew().isNegative()) {
            throw new IllegalStateException("JWT 时钟偏差不能为负数");
        }

        Resource privateKeyResource = resourceLoader.getResource(properties.getPrivateKey());
        Resource publicKeyResource = resourceLoader.getResource(properties.getPublicKey());
        try {
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(privateKeyResource.getInputStream());
            RSAPublicKey publicKey = RsaKeyConverters.x509().convert(publicKeyResource.getInputStream());
            if (privateKey == null || publicKey == null) {
                throw new IllegalArgumentException("RSA 密钥为空");
            }
            if (!privateKey.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalArgumentException("RSA 公私钥不匹配");
            }
            if (publicKey.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("RSA 密钥长度不足");
            }
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(properties.getKeyId())
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("JWT 密钥资源无效或无法读取");
        }
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey identityRsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(identityRsaKey));
    }

    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey identityRsaKey, IdentityJwtProperties properties) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(identityRsaKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .validateType(false)
                .build();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator(properties.getClockSkew());
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(properties.getIssuer()),
                timestampValidator,
                new LinkVerseJwtProfileValidator(
                        IdentityTokenProfile.USER_AUDIENCE,
                        IdentityTokenProfile.USER_TOKEN_USE,
                        null,
                        null,
                        IdentityTokenProfile.USER_SCOPE
                )
        ));
        return decoder;
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
    }

    private void requirePositiveDuration(Duration duration, String message) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException(message);
        }
    }
}
