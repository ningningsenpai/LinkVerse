package io.github.ningningsenpai.linkverse.observability.logging;

import java.util.Locale;
import java.util.Set;

/**
 * SensitiveDataSanitizer 为显式业务日志提供字段级脱敏，防止凭证和个人信息进入日志。
 *
 * @author ning
 * @date 2026-08-19
 */
public final class SensitiveDataSanitizer {

    public static final String MASKED_VALUE = "[已脱敏]";

    private static final Set<String> SENSITIVE_NAME_PARTS = Set.of(
            "authorization",
            "cookie",
            "password",
            "secret",
            "token",
            "private_key",
            "phone",
            "address",
            "id_card",
            "payment"
    );

    public String sanitize(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (fieldName == null) {
            return MASKED_VALUE;
        }
        String normalizedName = fieldName.toLowerCase(Locale.ROOT).replace('-', '_');
        boolean sensitive = SENSITIVE_NAME_PARTS.stream().anyMatch(normalizedName::contains);
        return sensitive ? MASKED_VALUE : value;
    }
}
