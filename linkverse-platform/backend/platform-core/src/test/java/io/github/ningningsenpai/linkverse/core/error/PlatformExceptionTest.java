package io.github.ningningsenpai.linkverse.core.error;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PlatformExceptionTest 验证安全错误说明不会被空值替代。
 *
 * @author ning
 * @date 2026-08-19
 */
class PlatformExceptionTest {

    @Test
    void shouldUseDefaultMessage() {
        PlatformException exception = new PlatformException(CommonErrorCode.CONFLICT);

        assertEquals("请求与当前资源状态冲突", exception.getMessage());
        assertEquals(CommonErrorCode.CONFLICT, exception.errorCode());
    }

    @Test
    void shouldRejectBlankSafeMessage() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformException(CommonErrorCode.INTERNAL_ERROR, " ")
        );

        assertEquals("错误提示不能为空", exception.getMessage());
    }
}
