package io.github.ningningsenpai.linkverse.identity.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * IdentityJdbcRegisteredClientRepository 将最小 auth_client 表适配为 Authorization Server 客户端仓储。
 *
 * @author ning
 * @date 2026-08-19
 */
@Repository
public class IdentityJdbcRegisteredClientRepository implements RegisteredClientRepository {

    public static final String AUDIENCE_SETTING = "linkverse.token.audience";

    private static final String FIND_BY_ID_SQL = """
            SELECT registered_client_id, client_id, client_secret_hash, client_name, audience, scopes,
                   access_token_ttl_seconds, secret_expires_at
            FROM auth_client
            WHERE registered_client_id = ? AND status = 'ACTIVE'
            """;

    private static final String FIND_BY_CLIENT_ID_SQL = """
            SELECT registered_client_id, client_id, client_secret_hash, client_name, audience, scopes,
                   access_token_ttl_seconds, secret_expires_at
            FROM auth_client
            WHERE client_id = ? AND status = 'ACTIVE'
            """;

    private static final String INSERT_SQL = """
            INSERT INTO auth_client (
                registered_client_id, client_id, client_secret_hash, client_name, status, audience, scopes,
                access_token_ttl_seconds, secret_expires_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_SQL = """
            UPDATE auth_client
            SET client_id = ?, client_secret_hash = ?, client_name = ?, audience = ?, scopes = ?,
                access_token_ttl_seconds = ?, secret_expires_at = ?, updated_at = ?
            WHERE registered_client_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdentityJdbcRegisteredClientRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        validateSupportedClient(registeredClient);
        Instant now = Instant.now();
        String audience = registeredClient.getClientSettings().getSetting(AUDIENCE_SETTING);
        String scopes = writeScopes(registeredClient.getScopes());
        Timestamp secretExpiresAt = registeredClient.getClientSecretExpiresAt() == null
                ? null
                : Timestamp.from(registeredClient.getClientSecretExpiresAt());

        int updated = jdbcTemplate.update(
                UPDATE_SQL,
                registeredClient.getClientId(),
                registeredClient.getClientSecret(),
                registeredClient.getClientName(),
                audience,
                scopes,
                Math.toIntExact(registeredClient.getTokenSettings().getAccessTokenTimeToLive().toSeconds()),
                secretExpiresAt,
                Timestamp.from(now),
                registeredClient.getId()
        );
        if (updated > 0) {
            return;
        }

        Instant createdAt = registeredClient.getClientIdIssuedAt() == null
                ? now
                : registeredClient.getClientIdIssuedAt();
        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    registeredClient.getId(),
                    registeredClient.getClientId(),
                    registeredClient.getClientSecret(),
                    registeredClient.getClientName(),
                    audience,
                    scopes,
                    Math.toIntExact(registeredClient.getTokenSettings().getAccessTokenTimeToLive().toSeconds()),
                    secretExpiresAt,
                    Timestamp.from(createdAt),
                    Timestamp.from(now)
            );
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("服务客户端标识已存在");
        }
    }

    @Override
    public RegisteredClient findById(String id) {
        return findOne(FIND_BY_ID_SQL, id);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return findOne(FIND_BY_CLIENT_ID_SQL, clientId);
    }

    private RegisteredClient findOne(String sql, String value) {
        RowMapper<RegisteredClient> rowMapper = (resultSet, rowNumber) -> {
                    Timestamp secretExpiresAt = resultSet.getTimestamp("secret_expires_at");
                    Set<String> scopes = readScopes(resultSet.getString("scopes"));

                    return RegisteredClient.withId(resultSet.getString("registered_client_id"))
                            .clientId(resultSet.getString("client_id"))
                            .clientSecret(resultSet.getString("client_secret_hash"))
                            .clientSecretExpiresAt(secretExpiresAt == null ? null : secretExpiresAt.toInstant())
                            .clientName(resultSet.getString("client_name"))
                            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                            .scopes(registeredScopes -> registeredScopes.addAll(scopes))
                            .clientSettings(ClientSettings.builder()
                                    .setting(AUDIENCE_SETTING, resultSet.getString("audience"))
                                    .build())
                            .tokenSettings(TokenSettings.builder()
                                    .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                                    .accessTokenTimeToLive(Duration.ofSeconds(
                                            resultSet.getInt("access_token_ttl_seconds")
                                    ))
                                    .build())
                            .build();
                };
        return jdbcTemplate.query(sql, rowMapper, value)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private String writeScopes(Set<String> scopes) {
        try {
            return objectMapper.writeValueAsString(scopes.stream().sorted().toList());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("服务客户端权限范围无法序列化");
        }
    }

    private Set<String> readScopes(String rawScopes) {
        try {
            List<String> scopes = objectMapper.readValue(rawScopes, new TypeReference<>() {
            });
            return scopes.stream()
                    .filter(scope -> scope != null && !scope.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("服务客户端权限范围格式无效");
        }
    }

    private void validateSupportedClient(RegisteredClient registeredClient) {
        if (!registeredClient.getClientAuthenticationMethods()
                .equals(Set.of(ClientAuthenticationMethod.CLIENT_SECRET_BASIC))) {
            throw new IllegalArgumentException("仅支持 client_secret_basic 客户端认证方式");
        }
        if (!registeredClient.getAuthorizationGrantTypes()
                .equals(Set.of(AuthorizationGrantType.CLIENT_CREDENTIALS))) {
            throw new IllegalArgumentException("仅支持 client_credentials 授权类型");
        }
        String audience = registeredClient.getClientSettings().getSetting(AUDIENCE_SETTING);
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("服务客户端必须配置令牌受众");
        }
        if (registeredClient.getClientSecret() == null
                || !registeredClient.getClientSecret().startsWith("{bcrypt}")) {
            throw new IllegalArgumentException("服务客户端密钥必须保存为 BCrypt 哈希");
        }
        long accessTokenTtlSeconds = registeredClient.getTokenSettings()
                .getAccessTokenTimeToLive()
                .toSeconds();
        if (accessTokenTtlSeconds < 60 || accessTokenTtlSeconds > 3600) {
            throw new IllegalArgumentException("服务访问令牌有效期须为 60 至 3600 秒");
        }
        if (registeredClient.getId() == null || registeredClient.getId().length() != 36) {
            throw new IllegalArgumentException("服务客户端内部标识必须为 UUID");
        }
    }
}
