package ning.linkverse.core.error;

import java.util.Objects;

/**
 * PlatformException 携带可安全返回给客户端的稳定错误码与中文说明。
 *
 * @author ning
 * @date 2026-08-19
 */
public final class PlatformException extends RuntimeException {

    private final ErrorCode errorCode;

    public PlatformException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public PlatformException(ErrorCode errorCode, String safeMessage) {
        super(requireMessage(safeMessage));
        this.errorCode = Objects.requireNonNull(errorCode, "错误码不能为空");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    private static String requireMessage(String safeMessage) {
        if (safeMessage == null || safeMessage.isBlank()) {
            throw new IllegalArgumentException("错误提示不能为空");
        }
        return safeMessage;
    }
}
