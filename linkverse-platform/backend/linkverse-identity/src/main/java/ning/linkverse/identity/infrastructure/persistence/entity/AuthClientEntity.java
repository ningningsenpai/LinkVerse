package ning.linkverse.identity.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * AuthClientEntity 是 auth_client 表的 MyBatis-Plus 持久化模型。
 *
 * @author ning
 * @date 2026-08-31
 */
@TableName("auth_client")
public class AuthClientEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String registeredClientId;
    private String clientId;
    private String clientSecretHash;
    private String clientName;
    private String status;
    private String audience;
    private String scopes;
    private int accessTokenTtlSeconds;
    private Instant secretExpiresAt;
    private Instant createdAt;
    private Instant updatedAt;

    public AuthClientEntity() {
    }

    public static AuthClientEntity active(
            String registeredClientId,
            String clientId,
            String clientSecretHash,
            String clientName,
            String audience,
            String scopes,
            int accessTokenTtlSeconds,
            Instant secretExpiresAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        AuthClientEntity entity = new AuthClientEntity();
        entity.registeredClientId = registeredClientId;
        entity.clientId = clientId;
        entity.clientSecretHash = clientSecretHash;
        entity.clientName = clientName;
        entity.status = "ACTIVE";
        entity.audience = audience;
        entity.scopes = scopes;
        entity.accessTokenTtlSeconds = accessTokenTtlSeconds;
        entity.secretExpiresAt = secretExpiresAt;
        entity.createdAt = createdAt;
        entity.updatedAt = updatedAt;
        return entity;
    }

    public String getRegisteredClientId() {
        return registeredClientId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecretHash() {
        return clientSecretHash;
    }

    public String getClientName() {
        return clientName;
    }

    public String getAudience() {
        return audience;
    }

    public String getScopes() {
        return scopes;
    }

    public int getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public Instant getSecretExpiresAt() {
        return secretExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
