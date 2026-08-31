package ning.linkverse.identity.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ning.linkverse.identity.infrastructure.persistence.entity.UserAccountEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * UserAccountMapper 提供 user_account 的 MyBatis-Plus 数据访问入口。
 *
 * @author ning
 * @date 2026-08-31
 */
@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {
}
