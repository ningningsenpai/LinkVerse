package ning.linkverse.identity.infrastructure.security;

import ning.linkverse.identity.infrastructure.persistence.IdentityJdbcRegisteredClientRepository;
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
 * TradeClientBootstrap 仅在显式启用时初始化 Trade 客户端，且不会在重启时静默轮换密钥。
 *
 * @author ning
 * @date 2026-08-19
 */
@Component
public class TradeClientBootstrap implements ApplicationRunner {

    private static final int MIN_SECRET_BYTES = 32;
    private static final int MAX_SECRET_BYTES = 72;

    private final TradeClientBootstrapProperties properties;
    private final IdentityJwtProperties jwtProperties;
    private final RegisteredClientRepository registeredClientRepository;
    private final PasswordEncoder passwordEncoder;

    public TradeClientBootstrap(
            TradeClientBootstrapProperties properties,
            IdentityJwtProperties jwtProperties,
            RegisteredClientRepository registeredClientRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.properties = properties;
        this.jwtProperties = jwtProperties;
        this.registeredClientRepository = registeredClientRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }
        validateProperties();
        RegisteredClient existingClient = registeredClientRepository.findByClientId(properties.getClientId());
        if (existingClient != null) {
            if (!passwordEncoder.matches(properties.getClientSecret(), existingClient.getClientSecret())) {
                throw new IllegalStateException("Trade 服务客户端密钥与已存摘要不匹配");
            }
            return;
        }

        RegisteredClient tradeClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(properties.getClientId())
                .clientIdIssuedAt(Instant.now())
                .clientSecret(passwordEncoder.encode(properties.getClientSecret()))
                .clientName("LinkVerse Trade")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(IdentityTokenProfile.PAYMENT_INTERNAL_SCOPE)
                .clientSettings(ClientSettings.builder()
                        .setting(
                                IdentityJdbcRegisteredClientRepository.AUDIENCE_SETTING,
                                IdentityTokenProfile.PAYMENT_AUDIENCE
                        )
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(jwtProperties.getServiceAccessTokenTtl())
                        .build())
                .build();
        registeredClientRepository.save(tradeClient);
    }

    private void validateProperties() {
        if (properties.getClientId() == null || properties.getClientId().isBlank()) {
            throw new IllegalStateException("Trade 服务客户端标识未配置");
        }
        int secretBytes = properties.getClientSecret() == null
                ? 0
                : properties.getClientSecret().getBytes(StandardCharsets.UTF_8).length;
        if (secretBytes < MIN_SECRET_BYTES || secretBytes > MAX_SECRET_BYTES) {
            throw new IllegalStateException("Trade 服务客户端密钥须为 32 至 72 个 UTF-8 字节");
        }
    }
}
