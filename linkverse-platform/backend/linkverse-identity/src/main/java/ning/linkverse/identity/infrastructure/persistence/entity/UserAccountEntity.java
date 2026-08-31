package ning.linkverse.identity.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import ning.linkverse.identity.domain.UserAccount;
import ning.linkverse.identity.domain.UserStatus;

import java.time.Instant;

/**
 * UserAccountEntity 是 user_account 表的 MyBatis-Plus 持久化模型。
 *
 * @author ning
 * @date 2026-08-31
 */
@TableName("user_account")
public class UserAccountEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String normalizedUsername;
    private String passwordHash;
    private UserStatus status;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public UserAccountEntity() {
    }

    public static UserAccountEntity fromDomain(UserAccount account) {
        UserAccountEntity entity = new UserAccountEntity();
        entity.id = account.id();
        entity.username = account.username();
        entity.normalizedUsername = account.normalizedUsername();
        entity.passwordHash = account.passwordHash();
        entity.status = account.status();
        entity.version = account.version();
        entity.createdAt = account.createdAt();
        entity.updatedAt = account.updatedAt();
        return entity;
    }

    public Long id() {
        return id;
    }

    public UserAccount toDomain() {
        return new UserAccount(
                id,
                username,
                normalizedUsername,
                passwordHash,
                status,
                version,
                createdAt,
                updatedAt
        );
    }
}
