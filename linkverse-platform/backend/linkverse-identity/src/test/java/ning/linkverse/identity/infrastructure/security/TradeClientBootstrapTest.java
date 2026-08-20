package ning.linkverse.identity.infrastructure.security;

import ning.linkverse.identity.infrastructure.persistence.IdentityJdbcRegisteredClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TradeClientBootstrapTest 验证 Trade 服务身份的初始化和防静默密钥漂移约束。
 *
 * @author ning
 * @date 2026-08-19
 */
class TradeClientBootstrapTest {

    private PasswordEncoder passwordEncoder;
    private IdentityJwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        passwordEncoder = new DelegatingPasswordEncoder(
                "bcrypt",
                Map.of("bcrypt", new BCryptPasswordEncoder(4))
        );
        jwtProperties = new IdentityJwtProperties();
        jwtProperties.setServiceAccessTokenTtl(Duration.ofMinutes(5));
    }

    @Test
    void shouldCreateExactTradeServiceIdentityAndAcceptSameSecretOnRestart() throws Exception {
        String rawSecret = "trade-service-secret-000000000000";
        InMemoryClientRepository repository = new InMemoryClientRepository();
        TradeClientBootstrap bootstrap = bootstrap(repository, rawSecret);

        bootstrap.run(new DefaultApplicationArguments(new String[0]));
        bootstrap.run(new DefaultApplicationArguments(new String[0]));

        RegisteredClient client = repository.client;
        assertThat(repository.saveCount).isEqualTo(1);
        assertThat(client.getClientId()).isEqualTo("linkverse-trade");
        assertThat(passwordEncoder.matches(rawSecret, client.getClientSecret())).isTrue();
        assertThat(client.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(client.getAuthorizationGrantTypes())
                .containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(client.getScopes()).containsExactly(IdentityTokenProfile.PAYMENT_INTERNAL_SCOPE);
        String audience = client.getClientSettings().getSetting(
                IdentityJdbcRegisteredClientRepository.AUDIENCE_SETTING
        );
        assertThat(audience).isEqualTo(IdentityTokenProfile.PAYMENT_AUDIENCE);
        assertThat(client.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void shouldFailWhenConfiguredSecretDiffersFromStoredHash() throws Exception {
        InMemoryClientRepository repository = new InMemoryClientRepository();
        bootstrap(repository, "trade-service-secret-000000000000")
                .run(new DefaultApplicationArguments(new String[0]));

        assertThatThrownBy(() -> bootstrap(repository, "different-secret-value-00000000000")
                .run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Trade 服务客户端密钥与已存摘要不匹配");
        assertThat(repository.saveCount).isEqualTo(1);
    }

    @Test
    void shouldRejectSecretOutsideBcryptByteBoundary() {
        InMemoryClientRepository repository = new InMemoryClientRepository();

        assertThatThrownBy(() -> bootstrap(repository, "x".repeat(73))
                .run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Trade 服务客户端密钥须为 32 至 72 个 UTF-8 字节");
        assertThat(repository.client).isNull();
    }

    private TradeClientBootstrap bootstrap(InMemoryClientRepository repository, String secret) {
        TradeClientBootstrapProperties properties = new TradeClientBootstrapProperties();
        properties.setEnabled(true);
        properties.setClientId("linkverse-trade");
        properties.setClientSecret(secret);
        return new TradeClientBootstrap(properties, jwtProperties, repository, passwordEncoder);
    }

    /**
     * InMemoryClientRepository 为启动测试保存单个客户端及写入次数。
     *
     * @author ning
     * @date 2026-08-19
     */
    private static final class InMemoryClientRepository implements RegisteredClientRepository {

        private RegisteredClient client;
        private int saveCount;

        @Override
        public void save(RegisteredClient registeredClient) {
            client = registeredClient;
            saveCount++;
        }

        @Override
        public RegisteredClient findById(String id) {
            return client != null && client.getId().equals(id) ? client : null;
        }

        @Override
        public RegisteredClient findByClientId(String clientId) {
            return client != null && client.getClientId().equals(clientId) ? client : null;
        }
    }
}
