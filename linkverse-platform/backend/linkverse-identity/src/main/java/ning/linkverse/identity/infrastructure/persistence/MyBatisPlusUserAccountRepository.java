package ning.linkverse.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ning.linkverse.core.error.PlatformException;
import ning.linkverse.identity.domain.IdentityErrorCode;
import ning.linkverse.identity.domain.UserAccount;
import ning.linkverse.identity.domain.UserAccountRepository;
import ning.linkverse.identity.infrastructure.persistence.entity.UserAccountEntity;
import ning.linkverse.identity.infrastructure.persistence.mapper.UserAccountMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * MyBatisPlusUserAccountRepository 使用 Identity Schema 保存账号事实，不向 API 暴露持久化模型。
 *
 * @author ning
 * @date 2026-08-31
 */
@Repository
public class MyBatisPlusUserAccountRepository implements UserAccountRepository {

    private final UserAccountMapper mapper;

    public MyBatisPlusUserAccountRepository(UserAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public UserAccount save(UserAccount account) {
        UserAccountEntity entity = UserAccountEntity.fromDomain(account);
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw new PlatformException(IdentityErrorCode.USERNAME_CONFLICT);
        }
        if (entity.id() == null) {
            throw new IllegalStateException("创建用户后未取得主键");
        }
        return entity.toDomain();
    }

    @Override
    public Optional<UserAccount> findByNormalizedUsername(String normalizedUsername) {
        UserAccountEntity entity = mapper.selectOne(Wrappers.<UserAccountEntity>query()
                .eq("normalized_username", normalizedUsername));
        return Optional.ofNullable(entity).map(UserAccountEntity::toDomain);
    }
}
