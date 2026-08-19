package io.github.ningningsenpai.linkverse.identity.application;

/**
 * LoginCommand 承载第一方登录所需的用户名和密码。
 *
 * @author ning
 * @date 2026-08-19
 */
public record LoginCommand(String username, String password) {
}
