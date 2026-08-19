package io.github.ningningsenpai.linkverse.identity.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ningningsenpai.linkverse.core.error.PlatformException;
import io.github.ningningsenpai.linkverse.identity.domain.IdentityErrorCode;
import io.github.ningningsenpai.linkverse.identity.domain.UserAccount;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IdentityMySqlRepositoryTest 在真实 MySQL 协议上验证 Flyway、唯一约束和两个 JDBC 仓储。
 *
 * @author ning
 * @date 2026-08-19
 */
@Testcontainers(disabledWithoutDocker = true)
class IdentityMySqlRepositoryTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("linkverse_mvp_identity")
            .withUsername("identity_test")
            .withPassword("identity_test_password");

    private static JdbcTemplate jdbcTemplate;

    private JdbcUserAccountRepository userRepository;
    private IdentityJdbcRegisteredClientRepository clientRepository;
    private PasswordEncoder passwordEncoder;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM auth_client");
        jdbcTemplate.update("DELETE FROM user_account");
        userRepository = new JdbcUserAccountRepository(jdbcTemplate);
        clientRepository = new IdentityJdbcRegisteredClientRepository(jdbcTemplate, new ObjectMapper());
        passwordEncoder = new DelegatingPasswordEncoder(
                "bcrypt",
                Map.of("bcrypt", new BCryptPasswordEncoder(4))
        );
    }

    @Test
    void shouldEnforceNormalizedUsernameUniquenessThroughRepository() {
        Instant now = Instant.parse("2026-08-19T10:15:30Z");
        UserAccount saved = userRepository.save(UserAccount.newActive(
                "Alice",
                "alice",
                passwordEncoder.encode("a-secure-password"),
                now
        ));

        assertThat(saved.id()).isPositive();
        assertThat(userRepository.findByNormalizedUsername("alice"))
                .contains(saved);
        assertThatThrownBy(() -> userRepository.save(UserAccount.newActive(
                "ＡＬＩＣＥ",
                "alice",
                passwordEncoder.encode("another-password"),
                now
        )))
                .isInstanceOf(PlatformException.class)
                .satisfies(exception -> assertThat(((PlatformException) exception).errorCode())
                        .isEqualTo(IdentityErrorCode.USERNAME_CONFLICT));
    }

    @Test
    void shouldPersistJsonScopesAndEnforceClientIdUniqueness() {
        RegisteredClient client = serviceClient(UUID.randomUUID().toString(), "linkverse-trade");

        clientRepository.save(client);

        RegisteredClient restored = clientRepository.findByClientId("linkverse-trade");
        assertThat(restored).isNotNull();
        assertThat(restored.getScopes()).containsExactly("payment.internal");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT JSON_TYPE(scopes) FROM auth_client WHERE client_id = ?",
                String.class,
                "linkverse-trade"
        )).isEqualTo("ARRAY");
        assertThatThrownBy(() -> clientRepository.save(serviceClient(
                UUID.randomUUID().toString(),
                "linkverse-trade"
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("服务客户端标识已存在");
    }

    private RegisteredClient serviceClient(String registeredClientId, String clientId) {
        return RegisteredClient.withId(registeredClientId)
                .clientId(clientId)
                .clientIdIssuedAt(Instant.parse("2026-08-19T10:15:30Z"))
                .clientSecret(passwordEncoder.encode("trade-service-secret-000000000000"))
                .clientName("LinkVerse Trade")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("payment.internal")
                .clientSettings(ClientSettings.builder()
                        .setting(
                                IdentityJdbcRegisteredClientRepository.AUDIENCE_SETTING,
                                "linkverse-payment"
                        )
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(5))
                        .build())
                .build();
    }
}
