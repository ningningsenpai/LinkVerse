package io.github.ningningsenpai.linkverse.observability.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PlatformObservabilityEnvironmentPostProcessor 提供可被应用配置覆盖的安全观测默认值。
 *
 * @author ning
 * @date 2026-08-19
 */
public final class PlatformObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "linkverseObservabilityDefaults";
    static final String NACOS_DEFAULT_LOGGING_CONFIG_ENABLED =
            "nacos.logging.default.config.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        configureNacosLogging(environment);
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("logging.structured.format.console", "logstash");
        defaults.put(
                "logging.structured.json.add.service",
                environment.getProperty("spring.application.name", "unknown")
        );
        defaults.put(
                "logging.structured.json.add.environment",
                environment.getProperty("spring.profiles.active", "default")
        );
        defaults.put("management.endpoint.health.probes.enabled", "true");
        defaults.put("management.endpoint.health.show-details", "never");
        defaults.put("management.endpoints.web.exposure.include", "health,info");
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    private void configureNacosLogging(ConfigurableEnvironment environment) {
        if (System.getProperty(NACOS_DEFAULT_LOGGING_CONFIG_ENABLED) != null) {
            return;
        }
        // Nacos 默认会加载独立 Logback 文件；关闭后复用应用的结构化日志配置。
        System.setProperty(
                NACOS_DEFAULT_LOGGING_CONFIG_ENABLED,
                environment.getProperty(NACOS_DEFAULT_LOGGING_CONFIG_ENABLED, "false")
        );
    }
}
