package ning.linkverse.identity.application;

/**
 * CredentialAuthenticator 校验用户凭据并返回可信用户主体。
 *
 * @author ning
 * @date 2026-08-19
 */
public interface CredentialAuthenticator {

    AuthenticatedUser authenticate(String normalizedUsername, String rawPassword);
}
