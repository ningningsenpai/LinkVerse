package io.github.ningningsenpai.linkverse.identity.application;

/**
 * UserAccessTokenIssuer 为已验证用户签发短期访问令牌。
 *
 * @author ning
 * @date 2026-08-19
 */
public interface UserAccessTokenIssuer {

    IssuedAccessToken issue(AuthenticatedUser user);
}
