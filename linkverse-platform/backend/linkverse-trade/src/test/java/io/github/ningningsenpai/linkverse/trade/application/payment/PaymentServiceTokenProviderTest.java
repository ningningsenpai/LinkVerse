package io.github.ningningsenpai.linkverse.trade.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

class PaymentServiceTokenProviderTest {

    @Test
    void shouldReturnServiceAccessToken() {
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        ClientRegistration registration = ClientRegistration
                .withRegistrationId(PaymentServiceTokenProvider.CLIENT_REGISTRATION_ID)
                .clientId("linkverse-trade")
                .clientSecret("测试密钥")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("http://linkverse-identity/oauth2/token")
                .build();
        OAuth2AccessToken token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "service-token",
                Instant.now(),
                Instant.now().plusSeconds(300));
        when(manager.authorize(any())).thenReturn(new OAuth2AuthorizedClient(
                registration,
                "linkverse-trade",
                token));

        assertThat(new PaymentServiceTokenProvider(manager).getAccessToken())
                .isEqualTo("service-token");
    }

    @Test
    void shouldFailClosedWhenAuthorizationServerReturnsNoClient() {
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any())).thenReturn(null);

        assertThatThrownBy(() -> new PaymentServiceTokenProvider(manager).getAccessToken())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("无法获取 Payment 服务访问令牌");
    }
}
