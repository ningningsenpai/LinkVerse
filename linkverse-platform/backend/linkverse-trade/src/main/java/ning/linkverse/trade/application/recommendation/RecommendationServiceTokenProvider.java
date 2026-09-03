package ning.linkverse.trade.application.recommendation;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RecommendationServiceTokenProvider 获取 Trade 调用 Recommendation 所需的专用服务令牌。
 *
 * @author ning
 * @date 2026-09-03
 */
@Component
public final class RecommendationServiceTokenProvider {

    private static final Authentication SERVICE_PRINCIPAL =
            UsernamePasswordAuthenticationToken.authenticated(
                    "linkverse-trade-recommendation",
                    "服务身份不使用本地密码",
                    List.of());

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public RecommendationServiceTokenProvider(OAuth2AuthorizedClientManager authorizedClientManager) {
        this.authorizedClientManager = authorizedClientManager;
    }

    public String getAccessToken() {
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                .withClientRegistrationId("linkverse-recommendation-client")
                .principal(SERVICE_PRINCIPAL)
                .build();
        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(request);
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            throw new IllegalStateException("无法获取 Recommendation 服务访问令牌");
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }
}
