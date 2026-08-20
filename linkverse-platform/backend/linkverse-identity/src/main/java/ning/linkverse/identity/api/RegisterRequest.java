package ning.linkverse.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * RegisterRequest 定义 MVP 用户注册的公开请求字段。
 *
 * @author ning
 * @date 2026-08-19
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名长度不能超过 64 个字符")
        String username,

        @NotNull(message = "密码不能为空")
        @Size(max = 128, message = "密码长度不能超过 128 个字符")
        String password
) {
}
