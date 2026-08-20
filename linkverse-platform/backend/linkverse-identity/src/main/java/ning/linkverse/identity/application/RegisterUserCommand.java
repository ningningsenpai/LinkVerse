package ning.linkverse.identity.application;

/**
 * RegisterUserCommand 承载注册用户所需的原始凭据。
 *
 * @author ning
 * @date 2026-08-19
 */
public record RegisterUserCommand(String username, String password) {
}
