package io.github.ningningsenpai.linkverse.identity.infrastructure.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthorizationServerComponentTest 以临时 RSA 密钥和 H2 验证完整注册、登录及 OAuth 协议链。
 *
 * @author ning
 * @date 2026-08-19
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:identity-authorization;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:identity-test-schema.sql",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "management.endpoints.enabled-by-default=false",
        "linkverse.security.jwt.issuer=http://localhost:18080",
        "linkverse.security.jwt.key-id=component-test-key",
        "linkverse.security.jwt.user-access-token-ttl=PT15M",
        "linkverse.security.jwt.service-access-token-ttl=PT5M",
        "linkverse.security.jwt.clock-skew=PT30S",
        "linkverse.security.trade-client.enabled=true",
        "linkverse.security.trade-client.client-id=linkverse-trade",
        "linkverse.security.trade-client.client-secret=trade-service-secret-000000000000"
})
@AutoConfigureMockMvc
class AuthorizationServerComponentTest {

    private static final String TRADE_SECRET = "trade-service-secret-000000000000";
    private static final KeyPair TEST_KEY_PAIR = generateTemporaryKeyPair();
    private static final Path PRIVATE_KEY_PATH = writePem(
            "PRIVATE KEY",
            TEST_KEY_PAIR.getPrivate().getEncoded()
    );
    private static final Path PUBLIC_KEY_PATH = writePem(
            "PUBLIC KEY",
            TEST_KEY_PAIR.getPublic().getEncoded()
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void jwtKeyProperties(DynamicPropertyRegistry registry) {
        registry.add("linkverse.security.jwt.private-key", () -> PRIVATE_KEY_PATH.toUri().toString());
        registry.add("linkverse.security.jwt.public-key", () -> PUBLIC_KEY_PATH.toUri().toString());
    }

    @Test
    void shouldRegisterLoginAndIssueSignedUserToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"Ａlice","password":"安全密码安全"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("Alice"))
                .andExpect(jsonPath("$.password").doesNotExist());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"安全密码安全"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(900))
                .andReturn();

        String token = json(loginResult).get("access_token").toString();
        SignedJWT jwt = verifiedJwt(token);
        assertThat(jwt.getHeader().getType().toString()).isEqualTo("at+jwt");
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("http://localhost:18080");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly(IdentityTokenProfile.USER_AUDIENCE);
        assertThat(jwt.getJWTClaimsSet().getSubject()).matches("\\d+");
        assertThat(jwt.getJWTClaimsSet().getJWTID()).isNotBlank();
        assertThat(jwt.getJWTClaimsSet().getStringClaim(IdentityTokenProfile.TOKEN_USE_CLAIM))
                .isEqualTo(IdentityTokenProfile.USER_TOKEN_USE);
        assertThat(jwt.getJWTClaimsSet().getStringClaim("scope"))
                .isEqualTo(IdentityTokenProfile.USER_SCOPE);
    }

    @Test
    void shouldIssueTradeServiceTokenAndExposeOnlyPublicJwk() throws Exception {
        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("linkverse-trade", TRADE_SECRET))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("scope", IdentityTokenProfile.PAYMENT_INTERNAL_SCOPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn();

        Map<String, Object> tokenResponse = json(tokenResult);
        assertThat(((Number) tokenResponse.get("expires_in")).longValue()).isBetween(299L, 300L);
        SignedJWT jwt = verifiedJwt(tokenResponse.get("access_token").toString());
        assertThat(jwt.getHeader().getType().toString()).isEqualTo("at+jwt");
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("component-test-key");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("http://localhost:18080");
        assertThat(jwt.getJWTClaimsSet().getAudience())
                .containsExactly(IdentityTokenProfile.PAYMENT_AUDIENCE);
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("linkverse-trade");
        assertThat(jwt.getJWTClaimsSet().getJWTID()).isNotBlank();
        assertThat(jwt.getJWTClaimsSet().getStringClaim(IdentityTokenProfile.TOKEN_USE_CLAIM))
                .isEqualTo(IdentityTokenProfile.SERVICE_TOKEN_USE);
        assertThat(jwt.getJWTClaimsSet().getStringClaim("client_id")).isEqualTo("linkverse-trade");
        assertScope(jwt.getJWTClaimsSet().getClaim("scope"), IdentityTokenProfile.PAYMENT_INTERNAL_SCOPE);
        Instant issuedAt = jwt.getJWTClaimsSet().getIssueTime().toInstant();
        Instant expiresAt = jwt.getJWTClaimsSet().getExpirationTime().toInstant();
        assertThat(Duration.between(issuedAt, expiresAt)).isEqualTo(Duration.ofMinutes(5));

        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").value("component-test-key"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].e").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist())
                .andExpect(jsonPath("$.keys[0].q").doesNotExist());
    }

    @Test
    void shouldReturnChineseOauthErrorsWithoutProblemDetailWrapping() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("linkverse-trade", "wrong-service-secret-00000000000"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("invalid_client"))
                .andExpect(jsonPath("$.error_description").value("客户端认证失败"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.detail").doesNotExist());

        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("linkverse-trade", TRADE_SECRET))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("scope", "payment.admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"))
                .andExpect(jsonPath("$.error_description").value("请求的权限范围无效"));
    }

    private SignedJWT verifiedJwt(String token) throws Exception {
        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) TEST_KEY_PAIR.getPublic()))).isTrue();
        return jwt;
    }

    private Map<String, Object> json(MvcResult result) throws Exception {
        return objectMapper.readValue(
                result.getResponse().getContentAsByteArray(),
                new TypeReference<>() {
                }
        );
    }

    private void assertScope(Object rawScope, String expectedScope) {
        if (rawScope instanceof Collection<?> scopes) {
            assertThat(scopes).hasSize(1);
            assertThat(scopes.iterator().next()).isEqualTo(expectedScope);
            return;
        }
        assertThat(rawScope).isEqualTo(expectedScope);
    }

    private static KeyPair generateTemporaryKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成测试 RSA 密钥", exception);
        }
    }

    private static Path writePem(String type, byte[] encodedKey) {
        try {
            Path path = Files.createTempFile("linkverse-identity-", ".pem");
            String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encodedKey);
            Files.writeString(
                    path,
                    "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n",
                    StandardCharsets.US_ASCII
            );
            path.toFile().deleteOnExit();
            return path;
        } catch (Exception exception) {
            throw new IllegalStateException("无法写入测试 RSA 密钥", exception);
        }
    }
}
