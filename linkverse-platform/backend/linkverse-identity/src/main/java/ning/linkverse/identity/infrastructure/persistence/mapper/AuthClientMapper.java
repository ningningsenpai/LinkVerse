package ning.linkverse.identity.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ning.linkverse.identity.infrastructure.persistence.entity.AuthClientEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/**
 * AuthClientMapper 提供 auth_client 的 MyBatis-Plus 数据访问和受控更新。
 *
 * @author ning
 * @date 2026-08-31
 */
@Mapper
public interface AuthClientMapper extends BaseMapper<AuthClientEntity> {

    @Update("""
            UPDATE auth_client
            SET client_id = #{clientId}, client_secret_hash = #{clientSecretHash},
                client_name = #{clientName}, audience = #{audience}, scopes = #{scopes},
                access_token_ttl_seconds = #{accessTokenTtlSeconds},
                secret_expires_at = #{secretExpiresAt}, updated_at = #{updatedAt}
            WHERE registered_client_id = #{registeredClientId}
            """)
    int updateClient(AuthClientEntity client);
}
