package io.github.ningningsenpai.linkverse.identity.infrastructure.security;

/**
 * IdentityTokenProfile 集中声明 Identity 签发令牌的稳定档案值。
 *
 * @author ning
 * @date 2026-08-19
 */
public final class IdentityTokenProfile {

    public static final String USER_AUDIENCE = "linkverse-api";
    public static final String PAYMENT_AUDIENCE = "linkverse-payment";
    public static final String TOKEN_USE_CLAIM = "token_use";
    public static final String USER_TOKEN_USE = "user";
    public static final String SERVICE_TOKEN_USE = "service";
    public static final String USER_SCOPE = "linkverse.user";
    public static final String PAYMENT_INTERNAL_SCOPE = "payment.internal";

    private IdentityTokenProfile() {
    }
}
