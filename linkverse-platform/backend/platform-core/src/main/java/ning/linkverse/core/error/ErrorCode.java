package ning.linkverse.core.error;

/**
 * ErrorCode 定义跨 Starter 共享的稳定错误契约，避免核心模块依赖 Web 框架。
 *
 * @author ning
 * @date 2026-08-19
 */
public interface ErrorCode {

    String code();

    int status();

    String defaultMessage();
}
