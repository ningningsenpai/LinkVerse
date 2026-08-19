package io.github.ningningsenpai.linkverse.identity.infrastructure.security;

import io.github.ningningsenpai.linkverse.core.error.PlatformException;
import io.github.ningningsenpai.linkverse.identity.application.AuthenticatedUser;
import io.github.ningningsenpai.linkverse.identity.application.CredentialAuthenticator;
import io.github.ningningsenpai.linkverse.identity.domain.IdentityErrorCode;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * SpringCredentialAuthenticator 通过 Spring Security 校验密码并隐藏失败原因。
 *
 * @author ning
 * @date 2026-08-19
 */
@Component
public class SpringCredentialAuthenticator implements CredentialAuthenticator {

    private final AuthenticationManager authenticationManager;

    public SpringCredentialAuthenticator(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    public AuthenticatedUser authenticate(String normalizedUsername, String rawPassword) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(normalizedUsername, rawPassword)
            );
            IdentityUserPrincipal principal = (IdentityUserPrincipal) authentication.getPrincipal();
            return new AuthenticatedUser(principal.userId(), principal.displayUsername());
        } catch (AuthenticationException exception) {
            throw new PlatformException(IdentityErrorCode.INVALID_CREDENTIALS);
        }
    }
}
