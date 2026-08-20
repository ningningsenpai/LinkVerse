package ning.linkverse.identity.application;

/**
 * AuthenticatedUser 表示已通过本地账号凭据验证的用户。
 *
 * @author ning
 * @date 2026-08-19
 */
public record AuthenticatedUser(long userId, String username) {
}
