package ning.linkverse.identity.infrastructure.security;

import ning.linkverse.identity.domain.UserAccount;
import ning.linkverse.identity.domain.UserStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * IdentityUserPrincipal 把身份域账号映射为 Spring Security 认证主体。
 *
 * @author ning
 * @date 2026-08-19
 */
public final class IdentityUserPrincipal implements UserDetails {

    private final UserAccount account;

    public IdentityUserPrincipal(UserAccount account) {
        this.account = account;
    }

    public long userId() {
        return account.id();
    }

    public String displayUsername() {
        return account.username();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return account.passwordHash();
    }

    @Override
    public String getUsername() {
        return account.normalizedUsername();
    }

    @Override
    public boolean isEnabled() {
        return account.status() == UserStatus.ACTIVE;
    }
}
