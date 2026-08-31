package ning.linkverse.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.identity.infrastructure.persistence.entity.AuthClientEntity;
import ning.linkverse.identity.infrastructure.persistence.mapper.AuthClientMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MyBatisPlusRegisteredClientRepository 将 auth_client 表适配为 Authorization Server 客户端仓储。
 *
 * @author ning
 * @date 2026-08-31
 */
@Repository
public class MyBatisPlusRegisteredClientRepository implements RegisteredClientRepository {

    public static final String AUDIENCE_SETTING = "linkverse.token.audience";

    private final AuthClientMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisPlusRegisteredClientRepository(AuthClientMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        validateSupportedClient(registeredClient);
        Instant now = Instant.now();
        Instant createdAt = registeredClient.getClientIdIssuedAt() == null
                ? now
                : registeredClient.getClientIdIssuedAt();
        AuthClientEntity entity = AuthClientEntity.active(
                registeredClient.getId(),
                registeredClient.getClientId(),
                registeredClient.getClientSecret(),
                registeredClient.getClientName(),
                registeredClient.getClientSettings().getSetting(AUDIENCE_SETTING),
                writeScopes(registeredClient.getScopes()),
                Math.toIntExact(registeredClient.getTokenSettings().getAccessTokenTimeToLive().toSeconds()),
                registeredClient.getClientSecretExpiresAt(),
                createdAt,
                now
        );

        if (mapper.updateClient(entity) > 0) {
            return;
        }
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("服务客户端标识已存在");
        }
    }

    @Override
    public RegisteredClient findById(String id) {
        return toRegisteredClient(mapper.selectOne(Wrappers.<AuthClientEntity>query()
                .eq("registered_client_id", id)
                .eq("status", "ACTIVE")));
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return toRegisteredClient(mapper.selectOne(Wrappers.<AuthClientEntity>query()
                .eq("client_id", clientId)
                .eq("status", "ACTIVE")));
    }

    private RegisteredClient toRegisteredClient(AuthClientEntity entity) {
        if (entity == null) {
            return null;
        }
        Set<String> scopes = readScopes(entity.getScopes());
        return RegisteredClient.withId(entity.getRegisteredClientId())
                .clientId(entity.getClientId())
                .clientSecret(entity.getClientSecretHash())
                .clientSecretExpiresAt(entity.getSecretExpiresAt())
                .clientName(entity.getClientName())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(registeredScopes -> registeredScopes.addAll(scopes))
                .clientSettings(ClientSettings.builder()
                        .setting(AUDIENCE_SETTING, entity.getAudience())
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .accessTokenTimeToLive(Duration.ofSeconds(entity.getAccessTokenTtlSeconds()))
                        .build())
                .build();
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
