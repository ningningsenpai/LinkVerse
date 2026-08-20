package ning.linkverse.identity.application;

import ning.linkverse.core.error.PlatformException;
import ning.linkverse.identity.domain.IdentityErrorCode;
import ning.linkverse.identity.domain.NormalizedUsername;
import ning.linkverse.identity.domain.UsernameNormalizer;
import org.springframework.stereotype.Service;

/**
 * LoginService 先验证本地凭据，再签发只包含可信用户主体的短期令牌。
 *
 * @author ning
 * @date 2026-08-19
 */
@Service
public class LoginService {

    private final CredentialAuthenticator credentialAuthenticator;
    private final UserAccessTokenIssuer userAccessTokenIssuer;

    public LoginService(
            CredentialAuthenticator credentialAuthenticator,
            UserAccessTokenIssuer userAccessTokenIssuer
    ) {
        this.credentialAuthenticator = credentialAuthenticator;
        this.userAccessTokenIssuer = userAccessTokenIssuer;
    }

    public IssuedAccessToken login(LoginCommand command) {
        NormalizedUsername username;
        try {
            username = UsernameNormalizer.normalize(command.username());
        } catch (PlatformException exception) {
            throw new PlatformException(IdentityErrorCode.INVALID_CREDENTIALS);
        }
        AuthenticatedUser user = credentialAuthenticator.authenticate(username.lookupValue(), command.password());
        return userAccessTokenIssuer.issue(user);
    }
}
