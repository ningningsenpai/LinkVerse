package ning.linkverse.observability.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlatformObservabilityEnvironmentPostProcessorTest 验证默认观测配置可被服务显式覆盖。
 *
 * @author ning
 * @date 2026-08-19
 */
class PlatformObservabilityEnvironmentPostProcessorTest {

    @Test
    void shouldAddStructuredLoggingAndSafeHealthDefaults() {
        String propertyName = PlatformObservabilityEnvironmentPostProcessor.NACOS_DEFAULT_LOGGING_CONFIG_ENABLED;
        String previousValue = System.getProperty(propertyName);
        try {
            System.clearProperty(propertyName);
            StandardEnvironment environment = new StandardEnvironment();

            new PlatformObservabilityEnvironmentPostProcessor().postProcessEnvironment(
                    environment,
                    new SpringApplication()
            );

            assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("logstash");
            assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
            assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
            assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info");
            assertThat(System.getProperty(propertyName)).isEqualTo("false");
        } finally {
            restoreSystemProperty(propertyName, previousValue);
        }
    }

    @Test
    void shouldKeepApplicationOverride() {
        String propertyName = PlatformObservabilityEnvironmentPostProcessor.NACOS_DEFAULT_LOGGING_CONFIG_ENABLED;
        String previousValue = System.getProperty(propertyName);
        try {
            System.clearProperty(propertyName);
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource(
                    "applicationOverride",
                    Map.of(
                            "logging.structured.format.console", "ecs",
                            propertyName, "true"
                    )
            ));

            new PlatformObservabilityEnvironmentPostProcessor().postProcessEnvironment(
                    environment,
                    new SpringApplication()
            );

            assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
            assertThat(System.getProperty(propertyName)).isEqualTo("true");
        } finally {
            restoreSystemProperty(propertyName, previousValue);
        }
    }

    private void restoreSystemProperty(String propertyName, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(propertyName);
            return;
        }
        System.setProperty(propertyName, previousValue);
    }
}
