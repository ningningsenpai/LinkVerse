package ning.linkverse.trade.infrastructure.export;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * RecommendationSnapshotExportRunner 在离线 profile 中执行一次快照导出后结束进程。
 *
 * @author ning
 * @date 2026-09-03
 */
@Component
@Profile("snapshot-export")
public class RecommendationSnapshotExportRunner implements ApplicationRunner {

    private final RecommendationSnapshotExporter exporter;
    private final ConfigurableApplicationContext context;

    public RecommendationSnapshotExportRunner(
            RecommendationSnapshotExporter exporter,
            ConfigurableApplicationContext context
    ) {
        this.exporter = exporter;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path snapshot = exporter.export();
        System.out.println("推荐快照中间文件已导出：" + snapshot);
        context.close();
    }
}
