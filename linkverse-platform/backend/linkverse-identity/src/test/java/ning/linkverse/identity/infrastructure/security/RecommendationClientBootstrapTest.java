package ning.linkverse.identity.infrastructure.security;

import ning.linkverse.identity.infrastructure.persistence.MyBatisPlusRegisteredClientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RecommendationClientBootstrapTest 验证推荐服务客户端的固定主体、受众和最小范围。
 *
 * @author ning
 * @date 2026-09-03
 */
class RecommendationClientBootstrapTest {

    @Test
    void shouldCreateDedicatedRecommendationClient() throws Exception {
        RecommendationClientBootstrapProperties properties = new RecommendationClientBootstrapProperties();
        properties.setEnabled(true);
        properties.setClientSecret("recommendation-secret-00000000000");
        IdentityJwtProperties jwt = new IdentityJwtProperties();
        jwt.setServiceAccessTokenTtl(Duration.ofMinutes(5));
        SingleClientRepository repository = new SingleClientRepository();

        new RecommendationClientBootstrap(
                properties, jwt, repository, new BCryptPasswordEncoder(4)
        ).run(new DefaultApplicationArguments(new String[0]));

        assertThat(repository.client.getClientId()).isEqualTo("linkverse-trade-recommendation");
        assertThat(repository.client.getScopes()).containsExactly("recommendation.internal");
        assertThat((String) repository.client.getClientSettings().getSetting(
                MyBatisPlusRegisteredClientRepository.AUDIENCE_SETTING
        )).isEqualTo("linkverse-recommendation");
    }

    private static final class SingleClientRepository implements RegisteredClientRepository {
        private RegisteredClient client;

        @Override
        public void save(RegisteredClient registeredClient) {
            client = registeredClient;
        }

        @Override
        public RegisteredClient findById(String id) {
            return null;
        }

        @Override
        public RegisteredClient findByClientId(String clientId) {
            return null;
        }
    }
}
