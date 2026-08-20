package ning.linkverse.observability.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SensitiveDataSanitizerTest 验证安全字段不会以原值写入业务日志。
 *
 * @author ning
 * @date 2026-08-19
 */
class SensitiveDataSanitizerTest {

    private final SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer();

    @Test
    void shouldMaskSensitiveValue() {
        assertThat(sanitizer.sanitize("Authorization", "Bearer secret-token"))
                .isEqualTo(SensitiveDataSanitizer.MASKED_VALUE);
        assertThat(sanitizer.sanitize("client_secret", "raw-secret"))
                .isEqualTo(SensitiveDataSanitizer.MASKED_VALUE);
    }

    @Test
    void shouldKeepNonSensitiveValue() {
        assertThat(sanitizer.sanitize("order_no", "order-1")).isEqualTo("order-1");
    }
}
