package ning.linkverse.gateway.security;

import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * GatewaySecurityTest 验证网关公共入口、用户令牌入口和内部路径隔离边界。
 *
 * @author ning
 * @date 2026-08-19
 */
@WebFluxTest(
        controllers = GatewaySecurityTest.TestEndpoint.class,
        properties = {
                "linkverse.security.issuer-uri=http://localhost:18080",
                "linkverse.security.jwk-set-uri=http://localhost:18081/oauth2/jwks"
        })
@Import({GatewaySecurityConfiguration.class, GatewaySecurityTest.TestEndpoint.class})
class GatewaySecurityTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean(name = "userJwtDecoder")
    private ReactiveJwtDecoder userJwtDecoder;

    @BeforeEach
    void configureDecoder() {
        when(userJwtDecoder.decode("valid-user-token")).thenReturn(Mono.just(userJwt()));
        when(userJwtDecoder.decode("invalid-token"))
                .thenReturn(Mono.error(new BadJwtException("测试令牌无效")));
    }

    @Test
    void shouldPermitOnlyDeclaredPublicAuthenticationEndpoints() {
        webTestClient.post().uri("/api/v1/auth/register")
                .exchange()
                .expectStatus().isOk();
        webTestClient.post().uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().isOk();
        webTestClient.post().uri("/oauth2/token")
                .exchange()
                .expectStatus().isOk();
        webTestClient.get().uri("/oauth2/jwks")
                .exchange()
                .expectStatus().isOk();
        webTestClient.get().uri("/.well-known/oauth-authorization-server")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void shouldPermitCorsPreflightWithoutToken() {
        webTestClient.options().uri("/api/v1/auth/register")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void shouldRequireUserTokenForProtectedApi() {
        webTestClient.get().uri("/api/v1/orders/test-only")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SECURITY_UNAUTHORIZED")
                .jsonPath("$.detail").isEqualTo("需要有效的用户访问令牌");
    }

    @Test
    void shouldAcceptDecodedUserTokenForProtectedApi() {
        webTestClient.get().uri("/api/v1/orders/test-only")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-user-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("用户接口已保护");
    }

    @Test
    void shouldRejectInvalidBearerToken() {
        webTestClient.get().uri("/api/v1/orders/test-only")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SECURITY_UNAUTHORIZED");
    }

    @Test
    void shouldDenyInternalPathEvenWithValidUserToken() {
        webTestClient.get().uri("/internal/v1/test-only")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-user-token")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SECURITY_FORBIDDEN");
    }

    @Test
    void shouldNotTreatForgedIdentityHeadersAsAuthentication() {
        webTestClient.get().uri("/api/v1/orders/test-only")
                .header("X-User-Id", "10001")
                .header("X-Client-Id", "linkverse-trade")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SECURITY_UNAUTHORIZED");
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
                .claim("jti", "gateway-user-jwt")
                .claim("token_use", "user")
                .claim("scope", "linkverse.user")
                .build();
    }

    /**
     * TestEndpoint 仅为安全过滤链测试提供可观察的公共、外部和内部端点。
     *
     * @author ning
     * @date 2026-08-19
     */
    @RestController
    public static class TestEndpoint {

        @PostMapping({"/api/v1/auth/register", "/api/v1/auth/login", "/oauth2/token"})
        public Map<String, String> authenticationEndpoint() {
            return Map.of("result", "公共认证入口");
        }

        @RequestMapping(path = "/api/v1/auth/register", method = RequestMethod.OPTIONS)
        public Map<String, String> preflightEndpoint() {
            return Map.of("result", "预检入口");
        }

        @GetMapping({"/oauth2/jwks", "/.well-known/oauth-authorization-server"})
        public Map<String, String> metadataEndpoint() {
            return Map.of("result", "公共元数据入口");
        }

        @GetMapping("/api/v1/orders/test-only")
        public String protectedEndpoint() {
            return "用户接口已保护";
        }

        @GetMapping("/internal/v1/test-only")
        public String internalEndpoint() {
            return "内部接口不应经网关访问";
        }
    }
}
