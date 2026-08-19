package io.github.ningningsenpai.linkverse.trade.infrastructure.security;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ningningsenpai.linkverse.security.jwt.LinkVerseJwtProfileValidator;
import io.github.ningningsenpai.linkverse.security.web.LinkVerseAccessDeniedHandler;
import io.github.ningningsenpai.linkverse.security.web.LinkVerseAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TradeResourceServerConfiguration 保证 Trade 直连时仍只接受用户令牌。
 *
 * @author ning
 * @date 2026-08-19
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class TradeResourceServerConfiguration {

    @Bean
    JwtDecoder tradeUserJwtDecoder(
            @Value("${linkverse.security.issuer-uri}") String issuer,
            @Value("${linkverse.security.jwk-set-uri}") String jwkSetUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .validateType(false)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(issuer),
                new LinkVerseJwtProfileValidator(
                        "linkverse-api",
                        "user",
                        null,
                        null,
                        "linkverse.user")));
        return decoder;
    }

    @Bean
    SecurityFilterChain tradeSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder tradeUserJwtDecoder,
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
                        .jwt(jwt -> jwt.decoder(tradeUserJwtDecoder))
                        .authenticationEntryPoint(new LinkVerseAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new LinkVerseAccessDeniedHandler(objectMapper)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LinkVerseAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new LinkVerseAccessDeniedHandler(objectMapper)))
                .build();
    }
}
