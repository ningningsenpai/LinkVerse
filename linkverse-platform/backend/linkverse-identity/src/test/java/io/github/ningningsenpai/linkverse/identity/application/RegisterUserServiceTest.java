package io.github.ningningsenpai.linkverse.identity.application;

import io.github.ningningsenpai.linkverse.core.error.PlatformException;
import io.github.ningningsenpai.linkverse.identity.domain.IdentityErrorCode;
import io.github.ningningsenpai.linkverse.identity.domain.UserAccount;
import io.github.ningningsenpai.linkverse.identity.domain.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RegisterUserServiceTest 验证注册用例只保存规范化用户名和密码摘要。
 *
 * @author ning
 * @date 2026-08-19
 */
class RegisterUserServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T10:15:30Z");

    @Test
    void shouldNormalizeUsernameAndPersistPasswordHash() {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        PasswordHasher passwordHasher = mock(PasswordHasher.class);
        when(passwordHasher.hash("a-secure-password")).thenReturn("{bcrypt}$2a$12$hashed");
        when(repository.save(any(UserAccount.class))).thenAnswer(invocation -> {
            UserAccount account = invocation.getArgument(0);
            return new UserAccount(
                    42L,
                    account.username(),
                    account.normalizedUsername(),
                    account.passwordHash(),
                    account.status(),
                    account.version(),
                    account.createdAt(),
                    account.updatedAt()
            );
        });
        RegisterUserService service = new RegisterUserService(
                repository,
                passwordHasher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        RegisterUserResult result = service.register(new RegisterUserCommand(
                "  Ａlice_01  ",
                "a-secure-password"
        ));

        assertThat(result).isEqualTo(new RegisterUserResult(42L, "Alice_01", NOW));
        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(repository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue()).satisfies(account -> {
            assertThat(account.username()).isEqualTo("Alice_01");
            assertThat(account.normalizedUsername()).isEqualTo("alice_01");
            assertThat(account.passwordHash()).isEqualTo("{bcrypt}$2a$12$hashed");
            assertThat(account.passwordHash()).doesNotContain("a-secure-password");
            assertThat(account.createdAt()).isEqualTo(NOW);
        });
    }

    @Test
    void shouldRejectPasswordLongerThanBcryptUtf8Boundary() {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        PasswordHasher passwordHasher = mock(PasswordHasher.class);
        RegisterUserService service = new RegisterUserService(
                repository,
                passwordHasher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.register(new RegisterUserCommand("alice", "x".repeat(73))))
                .isInstanceOf(PlatformException.class)
                .satisfies(exception -> assertThat(((PlatformException) exception).errorCode())
                        .isEqualTo(IdentityErrorCode.INVALID_PASSWORD));
        verify(passwordHasher, never()).hash(any());
        verify(repository, never()).save(any());
    }

    @Test
    void shouldAcceptMultiBytePasswordWithAtLeastTwelveUtf8Bytes() {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        PasswordHasher passwordHasher = mock(PasswordHasher.class);
        when(passwordHasher.hash("安全密码安全")).thenReturn("{bcrypt}$2a$12$hashed");
        when(repository.save(any(UserAccount.class))).thenAnswer(invocation -> {
            UserAccount account = invocation.getArgument(0);
            return new UserAccount(
                    7L,
                    account.username(),
                    account.normalizedUsername(),
                    account.passwordHash(),
                    account.status(),
                    account.version(),
                    account.createdAt(),
                    account.updatedAt()
            );
        });
        RegisterUserService service = new RegisterUserService(
                repository,
                passwordHasher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        RegisterUserResult result = service.register(new RegisterUserCommand("alice", "安全密码安全"));

        assertThat(result.userId()).isEqualTo(7L);
        verify(passwordHasher).hash("安全密码安全");
    }

    @Test
    void shouldRejectPasswordShorterThanTwelveUtf8Bytes() {
        UserAccountRepository repository = new UserAccountRepository() {
            @Override
            public UserAccount save(UserAccount account) {
                throw new AssertionError("无效密码不应写入账号仓储");
            }

            @Override
            public Optional<UserAccount> findByNormalizedUsername(String normalizedUsername) {
                return Optional.empty();
            }
        };
        RegisterUserService service = new RegisterUserService(
                repository,
                rawPassword -> {
                    throw new AssertionError("无效密码不应执行哈希");
                },
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.register(new RegisterUserCommand("alice", "x".repeat(11))))
                .isInstanceOf(PlatformException.class)
                .satisfies(exception -> assertThat(((PlatformException) exception).errorCode())
                        .isEqualTo(IdentityErrorCode.INVALID_PASSWORD));
    }
}
