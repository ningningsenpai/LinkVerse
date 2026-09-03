package ning.linkverse.identity.infrastructure.security;

import ning.linkverse.identity.infrastructure.persistence.MyBatisPlusRegisteredClientRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * RecommendationClientBootstrap 仅在显式启用时初始化 Trade 调用 Recommendation 的最小权限客户端。
 *
 * @author ning
 * @date 2026-09-03
 */
@Component
public class RecommendationClientBootstrap implements ApplicationRunner {

    private static final int MIN_SECRET_BYTES = 32;
    private static final int MAX_SECRET_BYTES = 72;

    private final RecommendationClientBootstrapProperties properties;
    private final IdentityJwtProperties jwtProperties;
    private final RegisteredClientRepository repository;
    private final PasswordEncoder passwordEncoder;

    public RecommendationClientBootstrap(
            RecommendationClientBootstrapProperties properties,
            IdentityJwtProperties jwtProperties,
            RegisteredClientRepository repository,
            PasswordEncoder passwordEncoder
    ) {
        this.properties = properties;
        this.jwtProperties = jwtProperties;
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }
        validateProperties();
        RegisteredClient existing = repository.findByClientId(properties.getClientId());
        if (existing != null) {
            if (!passwordEncoder.matches(properties.getClientSecret(), existing.getClientSecret())) {
                throw new IllegalStateException("Recommendation 服务客户端密钥与已存摘要不匹配");
            }
            return;
        }
        repository.save(RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(properties.getClientId())
                .clientIdIssuedAt(Instant.now())
                .clientSecret(passwordEncoder.encode(properties.getClientSecret()))
                .clientName("LinkVerse Trade Recommendation")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(IdentityTokenProfile.RECOMMENDATION_INTERNAL_SCOPE)
                .clientSettings(ClientSettings.builder()
                        .setting(MyBatisPlusRegisteredClientRepository.AUDIENCE_SETTING,
                                IdentityTokenProfile.RECOMMENDATION_AUDIENCE)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(jwtProperties.getServiceAccessTokenTtl())
                        .build())
                .build());
    }

    private void validateProperties() {
        if (!"linkverse-trade-recommendation".equals(properties.getClientId())) {
            throw new IllegalStateException("Recommendation 服务客户端标识必须为 linkverse-trade-recommendation");
        }
        int secretBytes = properties.getClientSecret() == null
                ? 0
                : properties.getClientSecret().getBytes(StandardCharsets.UTF_8).length;
        if (secretBytes < MIN_SECRET_BYTES || secretBytes > MAX_SECRET_BYTES) {
            throw new IllegalStateException("Recommendation 服务客户端密钥须为 32 至 72 个 UTF-8 字节");
        }
    }
}
