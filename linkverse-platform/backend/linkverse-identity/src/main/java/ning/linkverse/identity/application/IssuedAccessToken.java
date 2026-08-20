package ning.linkverse.identity.application;

/**
 * IssuedAccessToken 返回访问令牌及其秒级有效期。
 *
 * @author ning
 * @date 2026-08-19
 */
public record IssuedAccessToken(String tokenValue, long expiresInSeconds) {
}
