package ning.linkverse.identity.application;

import ning.linkverse.core.error.PlatformException;
import ning.linkverse.identity.domain.IdentityErrorCode;
import ning.linkverse.identity.domain.NormalizedUsername;
import ning.linkverse.identity.domain.UserAccount;
import ning.linkverse.identity.domain.UserAccountRepository;
import ning.linkverse.identity.domain.UsernameNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

/**
 * RegisterUserService 在单个身份域事务内规范化用户名并持久化密码哈希。
 *
 * @author ning
 * @date 2026-08-19
 */
@Service
public class RegisterUserService {

    private static final int MIN_PASSWORD_BYTES = 12;
    private static final int MAX_BCRYPT_BYTES = 72;

    private final UserAccountRepository userAccountRepository;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public RegisterUserService(
            UserAccountRepository userAccountRepository,
            PasswordHasher passwordHasher,
            Clock clock
    ) {
        this.userAccountRepository = userAccountRepository;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    @Transactional
    public RegisterUserResult register(RegisterUserCommand command) {
        NormalizedUsername username = UsernameNormalizer.normalize(command.username());
        validatePassword(command.password());

        Instant now = clock.instant();
        UserAccount account = UserAccount.newActive(
                username.displayValue(),
                username.lookupValue(),
                passwordHasher.hash(command.password()),
                now
        );
        UserAccount savedAccount = userAccountRepository.save(account);
        return new RegisterUserResult(savedAccount.id(), savedAccount.username(), savedAccount.createdAt());
    }

    private void validatePassword(String password) {
        int passwordBytes = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes < MIN_PASSWORD_BYTES || passwordBytes > MAX_BCRYPT_BYTES) {
            throw new PlatformException(IdentityErrorCode.INVALID_PASSWORD);
        }
    }
}
