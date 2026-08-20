package ning.linkverse.identity.infrastructure.security;

import ning.linkverse.identity.domain.UserAccountRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * IdentityUserDetailsService 从 Identity Schema 加载认证所需的最小账号信息。
 *
 * @author ning
 * @date 2026-08-19
 */
@Component
public class IdentityUserDetailsService implements UserDetailsService {

    private final UserAccountRepository userAccountRepository;

    public IdentityUserDetailsService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String normalizedUsername) throws UsernameNotFoundException {
        return userAccountRepository.findByNormalizedUsername(normalizedUsername)
                .map(IdentityUserPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("用户名或密码错误"));
    }
}
