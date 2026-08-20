package ning.linkverse.identity.domain;

import ning.linkverse.core.error.PlatformException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UsernameNormalizerTest 验证用户名的 Unicode 规范化与稳定比较规则。
 *
 * @author ning
 * @date 2026-08-19
 */
class UsernameNormalizerTest {

    @Test
    void shouldNormalizeNfkcTrimAndCaseForLookup() {
        NormalizedUsername username = UsernameNormalizer.normalize("  Ａlice_01  ");

        assertThat(username.displayValue()).isEqualTo("Alice_01");
        assertThat(username.lookupValue()).isEqualTo("alice_01");
    }

    @Test
    void shouldKeepUnicodeSymbolsAndCombiningMarksAfterNormalization() {
        NormalizedUsername username = UsernameNormalizer.normalize("a\u0338@😊");

        assertThat(username.displayValue()).isEqualTo("a\u0338@😊");
        assertThat(username.lookupValue()).isEqualTo("a\u0338@😊");
    }

    @Test
    void shouldRejectWhitespaceAndControlCharacters() {
        assertInvalid("alice smith");
        assertInvalid("alice\u00A0smith");
        assertInvalid("alice\u0000smith");
    }

    @Test
    void shouldRejectUsernameOutsideCodePointBoundary() {
        assertInvalid("ab");
        assertInvalid("😊".repeat(33));
    }

    private void assertInvalid(String username) {
        assertThatThrownBy(() -> UsernameNormalizer.normalize(username))
                .isInstanceOf(PlatformException.class)
                .satisfies(exception -> assertThat(((PlatformException) exception).errorCode())
                        .isEqualTo(IdentityErrorCode.INVALID_USERNAME));
    }
}
