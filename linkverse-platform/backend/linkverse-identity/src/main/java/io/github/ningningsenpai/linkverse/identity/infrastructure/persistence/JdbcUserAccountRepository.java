package io.github.ningningsenpai.linkverse.identity.infrastructure.persistence;

import io.github.ningningsenpai.linkverse.core.error.PlatformException;
import io.github.ningningsenpai.linkverse.identity.domain.IdentityErrorCode;
import io.github.ningningsenpai.linkverse.identity.domain.UserAccount;
import io.github.ningningsenpai.linkverse.identity.domain.UserAccountRepository;
import io.github.ningningsenpai.linkverse.identity.domain.UserStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Optional;

/**
 * JdbcUserAccountRepository 使用 Identity Schema 保存账号事实，不向 API 暴露数据库行模型。
 *
 * @author ning
 * @date 2026-08-19
 */
@Repository
public class JdbcUserAccountRepository implements UserAccountRepository {

    private static final String INSERT_SQL = """
            INSERT INTO user_account (
                username, normalized_username, password_hash, status, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_BY_NORMALIZED_USERNAME_SQL = """
            SELECT id, username, normalized_username, password_hash, status, version, created_at, updated_at
            FROM user_account
            WHERE normalized_username = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserAccount save(UserAccount account) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, account.username());
                statement.setString(2, account.normalizedUsername());
                statement.setString(3, account.passwordHash());
                statement.setString(4, account.status().name());
                statement.setLong(5, account.version());
                statement.setTimestamp(6, Timestamp.from(account.createdAt()));
                statement.setTimestamp(7, Timestamp.from(account.updatedAt()));
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException exception) {
            throw new PlatformException(IdentityErrorCode.USERNAME_CONFLICT);
        }

        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new IllegalStateException("创建用户后未取得主键");
        }
        return new UserAccount(
                generatedId.longValue(),
                account.username(),
                account.normalizedUsername(),
                account.passwordHash(),
                account.status(),
                account.version(),
                account.createdAt(),
                account.updatedAt()
        );
    }

    @Override
    public Optional<UserAccount> findByNormalizedUsername(String normalizedUsername) {
        return jdbcTemplate.query(
                        FIND_BY_NORMALIZED_USERNAME_SQL,
                        (resultSet, rowNumber) -> new UserAccount(
                                resultSet.getLong("id"),
                                resultSet.getString("username"),
                                resultSet.getString("normalized_username"),
                                resultSet.getString("password_hash"),
                                UserStatus.valueOf(resultSet.getString("status")),
                                resultSet.getLong("version"),
                                resultSet.getTimestamp("created_at").toInstant(),
                                resultSet.getTimestamp("updated_at").toInstant()
                        ),
                        normalizedUsername
                )
                .stream()
                .findFirst();
    }
}
