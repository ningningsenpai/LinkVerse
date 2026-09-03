package ning.linkverse.identity.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RecommendationClientBootstrapProperties 控制 Recommendation 专用服务客户端的首次初始化。
 *
 * @author ning
 * @date 2026-09-03
 */
@ConfigurationProperties(prefix = "linkverse.security.recommendation-client")
public class RecommendationClientBootstrapProperties {

    private boolean enabled;
    private String clientId = "linkverse-trade-recommendation";
    private String clientSecret;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }
}
