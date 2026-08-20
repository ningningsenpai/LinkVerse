package ning.linkverse.identity.domain;

import java.time.Instant;

/**
 * UserAccount 保存身份域认证所需的最小账号事实。
 *
 * @author ning
 * @date 2026-08-19
 */
public record UserAccount(
        Long id,
        String username,
        String normalizedUsername,
        String passwordHash,
        UserStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static UserAccount newActive(
            String username,
            String normalizedUsername,
            String passwordHash,
            Instant now
    ) {
        return new UserAccount(
                null,
                username,
                normalizedUsername,
                passwordHash,
                UserStatus.ACTIVE,
                0,
                now,
                now
        );
    }
}
