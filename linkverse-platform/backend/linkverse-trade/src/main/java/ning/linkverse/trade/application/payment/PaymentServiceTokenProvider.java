package ning.linkverse.trade.application.payment;

import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;

/**
 * PaymentServiceTokenProvider 获取 Trade 调用 Payment 内部接口所需的服务令牌。
 *
 * @author ning
 * @date 2026-08-19
 */
@Component
public final class PaymentServiceTokenProvider {

    static final String CLIENT_REGISTRATION_ID = "linkverse-payment-client";

    private static final Authentication SERVICE_PRINCIPAL =
            UsernamePasswordAuthenticationToken.authenticated(
                    "linkverse-trade",
                    "服务身份不使用本地密码",
                    List.of());

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public PaymentServiceTokenProvider(OAuth2AuthorizedClientManager authorizedClientManager) {
        this.authorizedClientManager = authorizedClientManager;
    }

    public String getAccessToken() {
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                .withClientRegistrationId(CLIENT_REGISTRATION_ID)
                .principal(SERVICE_PRINCIPAL)
                .build();
        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(request);
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            throw new IllegalStateException("无法获取 Payment 服务访问令牌");
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }
}
