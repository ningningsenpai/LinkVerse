package io.github.ningningsenpai.linkverse.core.error;

/**
 * CommonErrorCode 提供各服务都可复用且不会暴露内部实现的基础错误码。
 *
 * @author ning
 * @date 2026-08-19
 */
public enum CommonErrorCode implements ErrorCode {

    INVALID_REQUEST("COMMON_INVALID_REQUEST", 400, "请求参数不合法"),
    NOT_FOUND("COMMON_NOT_FOUND", 404, "请求的资源不存在"),
    CONFLICT("COMMON_CONFLICT", 409, "请求与当前资源状态冲突"),
    INTERNAL_ERROR("COMMON_INTERNAL_ERROR", 500, "系统暂时无法处理请求");

    private final String code;
    private final int status;
    private final String defaultMessage;

    CommonErrorCode(String code, int status, String defaultMessage) {
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
