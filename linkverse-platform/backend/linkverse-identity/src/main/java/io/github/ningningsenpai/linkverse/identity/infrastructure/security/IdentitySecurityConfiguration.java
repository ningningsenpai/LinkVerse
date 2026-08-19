package io.github.ningningsenpai.linkverse.identity.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ningningsenpai.linkverse.identity.infrastructure.persistence.IdentityJdbcRegisteredClientRepository;
import io.github.ningningsenpai.linkverse.security.web.LinkVerseAccessDeniedHandler;
import io.github.ningningsenpai.linkverse.security.web.LinkVerseAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IdentitySecurityConfiguration 隔离 OAuth2 协议端点与第一方 JSON API 的安全链。
 *
 * @author ning
 * @date 2026-08-19
 */
@Configuration(proxyBeanMethods = false)
public class IdentitySecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            OAuth2TokenErrorResponseHandler tokenErrorResponseHandler
    ) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();
        RequestMatcher endpointsMatcher = authorizationServer.getEndpointsMatcher();

        http.securityMatcher(endpointsMatcher)
                .with(authorizationServer, server -> server
                        .clientAuthentication(client -> client
                                .errorResponseHandler(tokenErrorResponseHandler))
                        .tokenEndpoint(token -> token
                                .errorResponseHandler(tokenErrorResponseHandler)))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers(endpointsMatcher))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain applicationSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            ObjectMapper objectMapper
    ) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(new LinkVerseAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new LinkVerseAccessDeniedHandler(objectMapper)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = Map.of("bcrypt", new BCryptPasswordEncoder(12));
        return new DelegatingPasswordEncoder("bcrypt", encoders);
    }

    @Bean
    AuthenticationManager authenticationManager(
            IdentityUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    OAuth2AuthorizationService authorizationService() {
        return new InMemoryOAuth2AuthorizationService();
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService() {
        return new InMemoryOAuth2AuthorizationConsentService();
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(IdentityJwtProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.getIssuer())
                .jwkSetEndpoint("/oauth2/jwks")
                .build();
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> serviceTokenCustomizer(IdentityJwtProperties properties) {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                    || !AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
                return;
            }
            String audience = context.getRegisteredClient()
                    .getClientSettings()
                    .getSetting(IdentityJdbcRegisteredClientRepository.AUDIENCE_SETTING);
            String clientId = context.getRegisteredClient().getClientId();
            context.getJwsHeader()
                    .type("at+jwt")
                    .keyId(properties.getKeyId());
            context.getClaims()
                    .subject(clientId)
                    .audience(List.of(audience))
                    .id(UUID.randomUUID().toString())
                    .claim(IdentityTokenProfile.TOKEN_USE_CLAIM, IdentityTokenProfile.SERVICE_TOKEN_USE)
                    .claim("client_id", clientId);
        };
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
