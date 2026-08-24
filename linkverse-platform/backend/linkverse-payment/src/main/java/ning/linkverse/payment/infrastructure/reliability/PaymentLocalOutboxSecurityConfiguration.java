package ning.linkverse.payment.infrastructure.reliability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * PaymentLocalOutboxSecurityConfiguration 只为本机交付脚本开放 Outbox 运维入口。
 *
 * @author ning
 * @date 2026-08-24
 */
@Profile({"local", "test"})
@Configuration(proxyBeanMethods = false)
public class PaymentLocalOutboxSecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain paymentLocalOutboxSecurityChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/internal/v1/outbox-events/**", "/internal/v1/reconciliation")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }
}
