package io.github.ningningsenpai.linkverse.trade.infrastructure.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * TradeResourceServerSecurityTest 验证 Trade 直连时仍执行用户令牌安全边界。
 *
 * @author ning
 * @date 2026-08-19
 */
@WebMvcTest(
        controllers = TradeResourceServerSecurityTest.TestEndpoint.class,
        properties = {
                "linkverse.security.issuer-uri=http://localhost:18080",
                "linkverse.security.jwk-set-uri=http://localhost:18081/oauth2/jwks"
        })
@Import({TradeResourceServerConfiguration.class, TradeResourceServerSecurityTest.TestEndpoint.class})
class TradeResourceServerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "tradeUserJwtDecoder")
    private JwtDecoder tradeUserJwtDecoder;

    @BeforeEach
    void configureDecoder() {
        when(tradeUserJwtDecoder.decode("valid-user-token")).thenReturn(userJwt());
        when(tradeUserJwtDecoder.decode("invalid-token"))
                .thenThrow(new BadJwtException("测试令牌无效"));
    }

    @Test
    void shouldPermitMinimalHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(content().string("服务信息公开"));
    }

    @Test
    void shouldRejectDirectRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/listings/test-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("SECURITY_UNAUTHORIZED"))
                .andExpect(jsonPath("$.detail").value("请提供有效的访问令牌"));
    }

    @Test
    void shouldAcceptDecodedUserTokenOnDirectRequest() throws Exception {
        mockMvc.perform(get("/api/v1/listings/test-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-user-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("用户接口已保护"));
    }

    @Test
    void shouldRejectInvalidBearerTokenOnDirectRequest() throws Exception {
        mockMvc.perform(get("/api/v1/listings/test-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SECURITY_UNAUTHORIZED"));
    }

    @Test
    void shouldDenyInternalPathToUserToken() throws Exception {
        mockMvc.perform(get("/internal/v1/test-only")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_FORBIDDEN"));
    }

    @Test
    void shouldNotTrustForgedIdentityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/listings/test-only")
                        .header("X-User-Id", "10001")
                        .header("X-Client-Id", "linkverse-trade"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SECURITY_UNAUTHORIZED"));
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
                .claim("jti", "trade-user-jwt")
                .claim("token_use", "user")
                .claim("scope", "linkverse.user")
                .build();
    }

    /**
     * TestEndpoint 仅为 Trade 安全过滤链测试提供可观察端点。
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

        @GetMapping("/api/v1/listings/test-only")
        public String protectedEndpoint() {
            return "用户接口已保护";
        }

        @GetMapping("/internal/v1/test-only")
        public String internalEndpoint() {
            return "内部接口不应向用户开放";
        }
    }
}
