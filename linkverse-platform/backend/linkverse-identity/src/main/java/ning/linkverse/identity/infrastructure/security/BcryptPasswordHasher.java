package ning.linkverse.identity.infrastructure.security;

import ning.linkverse.identity.application.PasswordHasher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BcryptPasswordHasher 使用带算法标识的 BCrypt12 哈希保存用户密码。
 *
 * @author ning
 * @date 2026-08-19
 */
@Component
public class BcryptPasswordHasher implements PasswordHasher {

    private final PasswordEncoder passwordEncoder;

    public BcryptPasswordHasher(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public String hash(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
}
