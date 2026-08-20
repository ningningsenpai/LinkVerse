package ning.linkverse.gateway.security;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.gateway.filter.GatewayRequestIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * GatewaySecurityConfiguration 负责用户令牌预校验，并冻结公共入口与内部路径边界。
 *
 * @author ning
 * @date 2026-08-19
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class GatewaySecurityConfiguration {

    @Bean
    ReactiveJwtDecoder userJwtDecoder(
            @Value("${linkverse.security.issuer-uri}") String issuer,
            @Value("${linkverse.security.jwk-set-uri}") String jwkSetUri) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .validateType(false)
                .build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(issuer),
                new AudienceValidator("linkverse-api"),
                new UserClaimsValidator());
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    SecurityWebFilterChain gatewaySecurityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder userJwtDecoder,
            ObjectMapper objectMapper) {
        GatewaySecurityErrorWriter errorWriter = new GatewaySecurityErrorWriter(objectMapper);
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .pathMatchers(HttpMethod.POST, "/oauth2/token").permitAll()
                        .pathMatchers(HttpMethod.GET, "/oauth2/jwks", "/.well-known/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/payments/callbacks/mock").permitAll()
                        .pathMatchers("/internal/**").denyAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtDecoder(userJwtDecoder))
                        .authenticationEntryPoint(errorWriter.authenticationEntryPoint())
                        .accessDeniedHandler(errorWriter.accessDeniedHandler()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorWriter.authenticationEntryPoint())
                        .accessDeniedHandler(errorWriter.accessDeniedHandler()))
                .build();
    }

    private static final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

        private static final OAuth2Error ERROR = new OAuth2Error(
                "invalid_token", "令牌受众不正确", null);

        private final String expectedAudience;

        private AudienceValidator(String expectedAudience) {
            this.expectedAudience = expectedAudience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            return jwt.getAudience().contains(expectedAudience)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(ERROR);
        }
    }

    private static final class UserClaimsValidator implements OAuth2TokenValidator<Jwt> {

        private static final OAuth2Error ERROR = new OAuth2Error(
                "invalid_token", "令牌缺少必要的用户身份声明", null);

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            boolean valid = "user".equals(jwt.getClaimAsString("token_use"))
                    && hasText(jwt.getSubject())
                    && hasText(jwt.getId())
                    && containsScope(jwt.getClaims().get("scope"), "linkverse.user");
            return valid
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(ERROR);
        }

        private static boolean containsScope(Object claim, String requiredScope) {
            if (claim instanceof String value) {
                return List.of(value.split("\\s+")).contains(requiredScope);
            }
            if (claim instanceof Collection<?> values) {
                return values.contains(requiredScope);
            }
            return false;
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    private static final class GatewaySecurityErrorWriter {

        private final ObjectMapper objectMapper;

        private GatewaySecurityErrorWriter(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        private ServerAuthenticationEntryPoint authenticationEntryPoint() {
            return (exchange, ignored) -> write(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    "SECURITY_UNAUTHORIZED",
                    "身份认证失败",
                    "需要有效的用户访问令牌");
        }

        private ServerAccessDeniedHandler accessDeniedHandler() {
            return (exchange, ignored) -> write(
                    exchange,
                    HttpStatus.FORBIDDEN,
                    "SECURITY_FORBIDDEN",
                    "访问被拒绝",
                    "当前身份无权访问该资源");
        }

        private Mono<Void> write(
                ServerWebExchange exchange,
                HttpStatus status,
                String code,
                String title,
                String detail) {
            String requestId = exchange.getAttributeOrDefault(
                    GatewayRequestIdFilter.REQUEST_ID_ATTRIBUTE,
                    exchange.getRequest().getHeaders().getFirst(GatewayRequestIdFilter.REQUEST_ID_HEADER));
            Map<String, Object> body = Map.of(
                    "type", "urn:linkverse:problem:" + code,
                    "title", title,
                    "status", status.value(),
                    "code", code,
                    "detail", detail,
                    "request_id", requestId == null ? "" : requestId,
                    "trace_id", traceId(exchange));
            byte[] bytes;
            try {
                bytes = objectMapper.writeValueAsBytes(body);
            } catch (Exception exception) {
                bytes = ("{\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        }

        private String traceId(ServerWebExchange exchange) {
            String traceparent = exchange.getRequest().getHeaders().getFirst("traceparent");
            if (traceparent == null) {
                return "";
            }
            String[] parts = traceparent.split("-");
            return parts.length >= 4 ? parts[1] : "";
        }
    }
}
