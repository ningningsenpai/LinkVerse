package io.github.ningningsenpai.linkverse.observability.autoconfigure;

import io.github.ningningsenpai.linkverse.observability.logging.SensitiveDataSanitizer;
import io.github.ningningsenpai.linkverse.observability.web.RequestIdFilter;
import io.github.ningningsenpai.linkverse.observability.web.RequestIdProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * PlatformObservabilityAutoConfiguration 为 Servlet 服务装配请求关联与脱敏基础能力。
 *
 * @author ning
 * @date 2026-08-19
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(RequestIdProperties.class)
public class PlatformObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RequestIdFilter requestIdFilter(RequestIdProperties properties) {
        return new RequestIdFilter(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    SensitiveDataSanitizer sensitiveDataSanitizer() {
        return new SensitiveDataSanitizer();
    }
}
