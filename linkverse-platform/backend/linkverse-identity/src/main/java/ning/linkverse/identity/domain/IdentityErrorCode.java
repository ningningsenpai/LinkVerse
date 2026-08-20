package ning.linkverse.identity.domain;

import ning.linkverse.core.error.ErrorCode;

/**
 * IdentityErrorCode 定义身份域对外稳定错误码与安全中文说明。
 *
 * @author ning
 * @date 2026-08-19
 */
public enum IdentityErrorCode implements ErrorCode {

    INVALID_USERNAME(
            "IDENTITY_INVALID_USERNAME",
            400,
            "用户名须为 3 至 32 个 Unicode 字符，且不能包含空白或控制字符"
    ),
    INVALID_PASSWORD(
            "IDENTITY_INVALID_PASSWORD",
            400,
            "密码须为 12 至 72 个 UTF-8 字节"
    ),
    USERNAME_CONFLICT("IDENTITY_USERNAME_CONFLICT", 409, "该用户名已被注册"),
    INVALID_CREDENTIALS("AUTH_INVALID_CREDENTIALS", 401, "用户名或密码错误");

    private final String code;
    private final int status;
    private final String defaultMessage;

    IdentityErrorCode(String code, int status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
