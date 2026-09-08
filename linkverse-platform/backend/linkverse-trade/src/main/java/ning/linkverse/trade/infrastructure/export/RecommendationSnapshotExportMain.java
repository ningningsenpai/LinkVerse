package ning.linkverse.trade.infrastructure.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.Clock;

/**
 * RecommendationSnapshotExportMain 为只读导出建立最小上下文，避免启动业务消费者和定时写任务。
 *
 * @author ning
 * @date 2026-09-06
 */
public final class RecommendationSnapshotExportMain {

    private RecommendationSnapshotExportMain() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(ExportConfiguration.class)
                .web(WebApplicationType.NONE).profiles("snapshot-export").run(args);
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("snapshot-export")
    @EnableAutoConfiguration
    @EnableTransactionManagement
    @Import({RecommendationSnapshotExporter.class, RecommendationSnapshotExportRunner.class})
    static class ExportConfiguration {

        @Bean
        Clock exportClock() {
            return Clock.systemUTC();
        }

        @Bean
        ObjectMapper exportObjectMapper() {
            return JsonMapper.builder().addModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        }
    }
}
