package ning.linkverse.payment.application;

import ning.linkverse.core.error.PlatformException;
import ning.linkverse.payment.domain.PaymentErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * MockHmacVerifier 对时间戳与原始请求字节做常量时间 HMAC 校验。
 *
 * @author ning
 * @date 2026-08-24
 */
@Component
@Profile({"local", "test"})
public class MockHmacVerifier {

    private final byte[] secret;
    private final Duration tolerance;
    private final Clock clock;

    public MockHmacVerifier(
            @Value("${linkverse.payment.mock-provider.secret}") String secret,
            @Value("${linkverse.payment.mock-provider.callback-tolerance:5m}") Duration tolerance,
            Clock clock
    ) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("Mock Provider HMAC 密钥至少需要32字节");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tolerance = tolerance;
        this.clock = clock;
    }

    public void verify(String timestamp, String signature, byte[] rawBody) {
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp);
        } catch (Exception exception) {
            throw new PlatformException(PaymentErrorCode.CALLBACK_EXPIRED);
        }
        Instant sentAt;
        try {
            sentAt = Instant.ofEpochSecond(epochSeconds);
        } catch (Exception exception) {
            throw new PlatformException(PaymentErrorCode.CALLBACK_EXPIRED);
        }
        if (Duration.between(sentAt, clock.instant()).abs().compareTo(tolerance) > 0) {
            throw new PlatformException(PaymentErrorCode.CALLBACK_EXPIRED);
        }
        byte[] expected = hmac(timestamp, rawBody);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(signature);
        } catch (Exception exception) {
            throw new PlatformException(PaymentErrorCode.CALLBACK_SIGNATURE_INVALID);
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new PlatformException(PaymentErrorCode.CALLBACK_SIGNATURE_INVALID);
        }
    }

    public String sign(String timestamp, byte[] rawBody) {
        return HexFormat.of().formatHex(hmac(timestamp, rawBody));
    }

    private byte[] hmac(String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return mac.doFinal(rawBody);
        } catch (Exception exception) {
            throw new IllegalStateException("运行环境不支持HmacSHA256", exception);
        }
    }
}
