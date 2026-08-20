package ning.linkverse.identity.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * IdentityJwtProperties 集中声明 Identity 的签发者、密钥资源和令牌时限。
 *
 * @author ning
 * @date 2026-08-19
 */
@ConfigurationProperties(prefix = "linkverse.security.jwt")
public class IdentityJwtProperties {

    private String issuer;
    private String keyId;
    private String privateKey;
    private String publicKey;
    private Duration userAccessTokenTtl = Duration.ofMinutes(15);
    private Duration serviceAccessTokenTtl = Duration.ofMinutes(5);
    private Duration clockSkew = Duration.ofSeconds(30);

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public Duration getUserAccessTokenTtl() {
        return userAccessTokenTtl;
    }

    public void setUserAccessTokenTtl(Duration userAccessTokenTtl) {
        this.userAccessTokenTtl = userAccessTokenTtl;
    }

    public Duration getServiceAccessTokenTtl() {
        return serviceAccessTokenTtl;
    }

    public void setServiceAccessTokenTtl(Duration serviceAccessTokenTtl) {
        this.serviceAccessTokenTtl = serviceAccessTokenTtl;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }
}
