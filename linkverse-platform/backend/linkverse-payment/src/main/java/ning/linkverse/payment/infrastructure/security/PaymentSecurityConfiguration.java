package ning.linkverse.payment.infrastructure.security;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.security.jwt.LinkVerseJwtProfileValidator;
import ning.linkverse.security.web.LinkVerseAccessDeniedHandler;
import ning.linkverse.security.web.LinkVerseAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * PaymentSecurityConfiguration 将用户接口、服务接口和待验签回调隔离为三条安全链。
 *
 * @author ning
 * @date 2026-08-19
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class PaymentSecurityConfiguration {

    @Bean("paymentUserJwtDecoder")
    JwtDecoder paymentUserJwtDecoder(
            @Value("${linkverse.security.issuer-uri}") String issuer,
            @Value("${linkverse.security.jwk-set-uri}") String jwkSetUri) {
        return jwtDecoder(
                issuer,
                jwkSetUri,
                new LinkVerseJwtProfileValidator(
                        "linkverse-api",
                        "user",
                        null,
                        null,
                        "linkverse.user"));
    }

    @Bean("paymentServiceJwtDecoder")
    JwtDecoder paymentServiceJwtDecoder(
            @Value("${linkverse.security.issuer-uri}") String issuer,
            @Value("${linkverse.security.jwk-set-uri}") String jwkSetUri) {
        return jwtDecoder(
                issuer,
                jwkSetUri,
                new LinkVerseJwtProfileValidator(
                        "linkverse-payment",
                        "service",
                        "linkverse-trade",
                        "linkverse-trade",
                        "payment.internal"));
    }

    @Bean
    @Order(1)
    SecurityFilterChain unsignedProviderCallbackChain(HttpSecurity http, ObjectMapper objectMapper)
            throws Exception {
        // Provider 验签器落地前保持精确路径关闭，避免空回调入口被误开放。
        return http
                .securityMatcher(request -> "POST".equals(request.getMethod())
                        && "/api/v1/payments/callbacks/mock".equals(request.getRequestURI()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LinkVerseAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new LinkVerseAccessDeniedHandler(objectMapper)))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain paymentInternalSecurityChain(
            HttpSecurity http,
            @Qualifier("paymentServiceJwtDecoder") JwtDecoder serviceJwtDecoder,
            ObjectMapper objectMapper) throws Exception {
        return resourceServerChain(
                http.securityMatcher("/internal/v1/**"),
                serviceJwtDecoder,
                objectMapper);
    }

    @Bean
    @Order(3)
    SecurityFilterChain paymentExternalSecurityChain(
            HttpSecurity http,
            @Qualifier("paymentUserJwtDecoder") JwtDecoder userJwtDecoder,
            ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/internal/**").denyAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(userJwtDecoder))
                        .authenticationEntryPoint(new LinkVerseAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new LinkVerseAccessDeniedHandler(objectMapper)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LinkVerseAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new LinkVerseAccessDeniedHandler(objectMapper)))
                .build();
    }

    private SecurityFilterChain resourceServerChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(new LinkVerseAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new LinkVerseAccessDeniedHandler(objectMapper)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LinkVerseAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new LinkVerseAccessDeniedHandler(objectMapper)))
                .build();
    }

    private JwtDecoder jwtDecoder(
            String issuer,
            String jwkSetUri,
            LinkVerseJwtProfileValidator profileValidator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .validateType(false)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(issuer),
                profileValidator));
        return decoder;
    }
}
