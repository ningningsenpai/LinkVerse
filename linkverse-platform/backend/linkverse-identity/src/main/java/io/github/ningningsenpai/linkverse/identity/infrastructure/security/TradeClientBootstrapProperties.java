package io.github.ningningsenpai.linkverse.identity.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TradeClientBootstrapProperties 控制首次启动时的 Trade 服务客户端初始化。
 *
 * @author ning
 * @date 2026-08-19
 */
@ConfigurationProperties(prefix = "linkverse.security.trade-client")
public class TradeClientBootstrapProperties {

    private boolean enabled;
    private String clientId = "linkverse-trade";
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
