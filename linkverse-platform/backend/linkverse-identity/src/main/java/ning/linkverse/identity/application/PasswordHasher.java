package ning.linkverse.identity.application;

/**
 * PasswordHasher 隔离应用层与具体的自适应密码哈希实现。
 *
 * @author ning
 * @date 2026-08-19
 */
public interface PasswordHasher {

    String hash(String rawPassword);
}
