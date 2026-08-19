package io.github.ningningsenpai.linkverse.web.autoconfigure;

import io.github.ningningsenpai.linkverse.web.error.PlatformProblemExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * PlatformWebAutoConfiguration 为 Servlet MVC 服务装配统一的中文错误协议。
 *
 * @author ning
 * @date 2026-08-19
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PlatformWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PlatformProblemExceptionHandler platformProblemExceptionHandler() {
        return new PlatformProblemExceptionHandler();
    }
}
