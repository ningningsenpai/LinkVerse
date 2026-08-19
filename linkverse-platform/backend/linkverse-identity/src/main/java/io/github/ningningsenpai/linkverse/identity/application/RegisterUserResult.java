package io.github.ningningsenpai.linkverse.identity.application;

import java.time.Instant;

/**
 * RegisterUserResult 返回新账号的公开身份信息。
 *
 * @author ning
 * @date 2026-08-19
 */
public record RegisterUserResult(long userId, String username, Instant createdAt) {
}
