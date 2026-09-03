package ning.linkverse.trade.application.recommendation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * UserKeyHasher 使用外部 HMAC Secret 产生可稳定关联但不暴露用户 ID 的键。
 *
 * @author ning
 * @date 2026-09-03
 */
@Component
public class UserKeyHasher {

    private final byte[] secret;

    public UserKeyHasher(@Value("${linkverse.trade.recommendation-user-hmac-secret:}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(long userId) {
        if (secret.length < 32) {
            throw new IllegalStateException("推荐用户 HMAC 密钥至少需要 32 个 UTF-8 字节");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(Long.toString(userId).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("运行环境不支持 HmacSHA256", exception);
        }
    }
}
