package ning.linkverse.identity.domain;

import ning.linkverse.core.error.PlatformException;

import java.text.Normalizer;
import java.util.Locale;

/**
 * UsernameNormalizer 统一账号注册和登录时的用户名比较规则。
 *
 * @author ning
 * @date 2026-08-19
 */
public final class UsernameNormalizer {

    private static final int MIN_LENGTH = 3;
    private static final int MAX_LENGTH = 32;

    private UsernameNormalizer() {
    }

    public static NormalizedUsername normalize(String rawUsername) {
        if (rawUsername == null) {
            throw new PlatformException(IdentityErrorCode.INVALID_USERNAME);
        }

        String displayValue = Normalizer.normalize(rawUsername.strip(), Normalizer.Form.NFKC);
        int codePointLength = displayValue.codePointCount(0, displayValue.length());
        if (codePointLength < MIN_LENGTH || codePointLength > MAX_LENGTH) {
            throw new PlatformException(IdentityErrorCode.INVALID_USERNAME);
        }
        if (displayValue.codePoints().anyMatch(UsernameNormalizer::isWhitespaceOrControl)) {
            throw new PlatformException(IdentityErrorCode.INVALID_USERNAME);
        }

        return new NormalizedUsername(displayValue, displayValue.toLowerCase(Locale.ROOT));
    }

    private static boolean isWhitespaceOrControl(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || Character.isISOControl(codePoint);
    }
}
