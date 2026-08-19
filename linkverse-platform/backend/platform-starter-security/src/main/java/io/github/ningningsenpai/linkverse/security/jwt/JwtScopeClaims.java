package io.github.ningningsenpai.linkverse.security.jwt;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * JwtScopeClaims 统一解析字符串或集合形式的 scope/scp 声明。
 *
 * @author ning
 * @date 2026-08-19
 */
public final class JwtScopeClaims {

    private JwtScopeClaims() {
    }

    public static Set<String> parse(Jwt jwt) {
        Objects.requireNonNull(jwt, "JWT 不能为空");
        Object rawScopes = jwt.getClaims().get("scope");
        if (rawScopes == null) {
            rawScopes = jwt.getClaims().get("scp");
        }
        return parseValue(rawScopes);
    }

    static Set<String> parseValue(Object rawScopes) {
        if (rawScopes instanceof String value) {
            if (value.isBlank()) {
                return Set.of();
            }
            return Arrays.stream(value.trim().split("\\s+"))
                    .filter(scope -> !scope.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        if (rawScopes instanceof Collection<?> values) {
            LinkedHashSet<String> scopes = new LinkedHashSet<>();
            for (Object value : values) {
                if (value instanceof String scope && !scope.isBlank()) {
                    scopes.add(scope);
                }
            }
            return Set.copyOf(scopes);
        }
        return Set.of();
    }
}
