package io.github.ningningsenpai.linkverse.payment.infrastructure.security;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PaymentSecurityTest 验证 Payment 用户、服务和待验签回调三条安全链互不混用。
 *
 * @author ning
 * @date 2026-08-19
 */
@WebMvcTest(
        controllers = PaymentSecurityTest.TestEndpoint.class,
        properties = {
                "linkverse.security.issuer-uri=http://localhost:18080",
                "linkverse.security.jwk-set-uri=http://localhost:18081/oauth2/jwks"
        })
@Import({PaymentSecurityConfiguration.class, PaymentSecurityTest.TestEndpoint.class})
class PaymentSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "paymentUserJwtDecoder")
    private JwtDecoder paymentUserJwtDecoder;

    @MockitoBean(name = "paymentServiceJwtDecoder")
    private JwtDecoder paymentServiceJwtDecoder;

    @BeforeEach
    void configureDecoders() {
        when(paymentUserJwtDecoder.decode("valid-user-token")).thenReturn(userJwt());
        when(paymentUserJwtDecoder.decode("valid-service-token"))
                .thenThrow(new BadJwtException("服务令牌不能用于用户接口"));
        when(paymentServiceJwtDecoder.decode("valid-service-token")).thenReturn(serviceJwt());
        when(paymentServiceJwtDecoder.decode("valid-user-token"))
                .thenThrow(new BadJwtException("用户令牌不能用于内部接口"));
    }

    @Test
    void shouldPermitMinimalHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(content().string("服务信息公开"));
    }

    @Test
    void shouldRequireUserTokenForExternalApi() throws Exception {
        mockMvc.perform(get("/api/v1/payment-intents/test-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("SECURITY_UNAUTHORIZED"));
    }

    @Test
    void shouldAcceptUserTokenForExternalApi() throws Exception {
        mockMvc.perform(get("/api/v1/payment-intents/test-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-user-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("用户支付接口已保护"));
    }

    @Test
    void shouldRejectServiceTokenForExternalApi() throws Exception {
        mockMvc.perform(get("/api/v1/payment-intents/test-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-service-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SECURITY_UNAUTHORIZED"));
    }

    @Test
    void shouldRejectUserTokenForInternalApi() throws Exception {
        mockMvc.perform(get("/internal/v1/test-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-user-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SECURITY_UNAUTHORIZED"));
    }

    @Test
    void shouldAcceptTradeServiceTokenForInternalApi() throws Exception {
        mockMvc.perform(get("/internal/v1/test-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-service-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("Trade 服务接口已保护"));
    }

    @Test
    void shouldDenyUndeclaredInternalNamespaceToUserToken() throws Exception {
        mockMvc.perform(get("/internal/v2/test-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_FORBIDDEN"));
    }

    @Test
    void shouldKeepExactMockCallbackClosedForAuthenticatedUser() throws Exception {
        mockMvc.perform(post("/api/v1/payments/callbacks/mock").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_FORBIDDEN"));
    }

    private Jwt userJwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("valid-user-token")
                .header("alg", "RS256")
                .header("typ", "at+jwt")
                .issuer("http://localhost:18080")
                .subject("10001")
                .audience(List.of("linkverse-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim("jti", "payment-user-jwt")
                .claim("token_use", "user")
                .claim("scope", "linkverse.user")
                .build();
    }

    private Jwt serviceJwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("valid-service-token")
                .header("alg", "RS256")
                .header("typ", "at+jwt")
                .issuer("http://localhost:18080")
                .subject("linkverse-trade")
                .audience(List.of("linkverse-payment"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("jti", "payment-service-jwt")
                .claim("client_id", "linkverse-trade")
                .claim("token_use", "service")
                .claim("scope", "payment.internal")
                .build();
    }

    /**
     * TestEndpoint 仅为 Payment 安全过滤链测试提供可观察端点。
     *
     * @author ning
     * @date 2026-08-19
     */
    @RestController
    public static class TestEndpoint {

        @GetMapping("/actuator/info")
        public String serviceInfo() {
            return "服务信息公开";
        }

        @GetMapping("/api/v1/payment-intents/test-only")
        public String externalEndpoint() {
            return "用户支付接口已保护";
        }

        @GetMapping("/internal/v1/test-only")
        public String internalEndpoint() {
            return "Trade 服务接口已保护";
        }

        @PostMapping("/api/v1/payments/callbacks/mock")
        public String callbackEndpoint() {
            return "阶段 2 不得进入回调处理";
        }
    }
}
