package ning.linkverse.identity.domain;

import java.util.Optional;

/**
 * UserAccountRepository 定义身份域账号事实的持久化边界。
 *
 * @author ning
 * @date 2026-08-19
 */
public interface UserAccountRepository {

    UserAccount save(UserAccount account);

    Optional<UserAccount> findByNormalizedUsername(String normalizedUsername);
}
