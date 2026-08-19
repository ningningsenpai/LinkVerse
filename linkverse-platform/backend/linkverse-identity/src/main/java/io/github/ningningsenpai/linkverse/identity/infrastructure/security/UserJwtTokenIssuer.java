package io.github.ningningsenpai.linkverse.identity.infrastructure.security;

import io.github.ningningsenpai.linkverse.identity.application.AuthenticatedUser;
import io.github.ningningsenpai.linkverse.identity.application.IssuedAccessToken;
import io.github.ningningsenpai.linkverse.identity.application.UserAccessTokenIssuer;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * UserJwtTokenIssuer 使用 Identity 私钥签发不可由请求体伪造主体的用户 JWT。
 *
 * @author ning
 * @date 2026-08-19
 */
@Component
public class UserJwtTokenIssuer implements UserAccessTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final IdentityJwtProperties properties;
    private final Clock clock;

    public UserJwtTokenIssuer(JwtEncoder jwtEncoder, IdentityJwtProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public IssuedAccessToken issue(AuthenticatedUser user) {
        Instant issuedAt = clock.instant();
        Duration timeToLive = properties.getUserAccessTokenTtl();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .subject(Long.toString(user.userId()))
                .audience(List.of(IdentityTokenProfile.USER_AUDIENCE))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(timeToLive))
                .id(UUID.randomUUID().toString())
                .claim(IdentityTokenProfile.TOKEN_USE_CLAIM, IdentityTokenProfile.USER_TOKEN_USE)
                .claim("scope", IdentityTokenProfile.USER_SCOPE)
                .build();
        JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256)
                .type("at+jwt")
                .keyId(properties.getKeyId())
                .build();
        String tokenValue = jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
        return new IssuedAccessToken(tokenValue, timeToLive.toSeconds());
    }
}
