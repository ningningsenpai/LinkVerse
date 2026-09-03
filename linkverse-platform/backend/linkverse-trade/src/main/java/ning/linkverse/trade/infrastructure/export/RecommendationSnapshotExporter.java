package ning.linkverse.trade.infrastructure.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RecommendationSnapshotExporter 使用只读 Trade 连接导出匿名、版本化的 JSONL 中间快照。
 *
 * @author ning
 * @date 2026-09-03
 */
@Component
@Profile("snapshot-export")
public class RecommendationSnapshotExporter {

    private static final DateTimeFormatter VERSION_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Path outputRoot;
    private final byte[] hmacSecret;

    public RecommendationSnapshotExporter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${linkverse.trade.snapshot-output}") Path outputRoot,
            @Value("${linkverse.trade.recommendation-user-hmac-secret}") String hmacSecret
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.hmacSecret = hmacSecret.getBytes(StandardCharsets.UTF_8);
    }

    public Path export() {
        if (hmacSecret.length < 32) {
            throw new IllegalStateException("推荐用户 HMAC 密钥至少需要 32 个 UTF-8 字节");
        }
        Instant now = clock.instant();
        Long watermark = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM trade_behavior_event", Long.class
        );
        String version = "trade-" + VERSION_TIME.format(now) + "-" + watermark;
        Path target = outputRoot.resolve(version);
        try {
            Files.createDirectories(target);
            long objectRows = writeObjects(target.resolve("objects.jsonl"));
            long eventRows = writeEvents(target.resolve("events.jsonl"), watermark == null ? 0L : watermark);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schema_version", 1);
            manifest.put("domain", "trade");
            manifest.put("data_source", "REAL");
            manifest.put("snapshot_version", version);
            manifest.put("watermark", watermark);
            manifest.put("exported_at", now);
            manifest.put("user_key", "HMAC-SHA256");
            manifest.put("objects", objectRows);
            manifest.put("events", eventRows);
            manifest.put("objects_sha256", sha256(target.resolve("objects.jsonl")));
            manifest.put("events_sha256", sha256(target.resolve("events.jsonl")));
            manifest.put("stage", "JSONL_READY_FOR_PARQUET");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("manifest.json").toFile(), manifest);
            Path temporaryPointer = outputRoot.resolve("latest-export.txt.tmp");
            Files.writeString(temporaryPointer, target.toString(), StandardCharsets.UTF_8);
            Files.move(
                    temporaryPointer,
                    outputRoot.resolve("latest-export.txt"),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
            return target;
        } catch (Exception exception) {
            throw new IllegalStateException("导出推荐快照失败", exception);
        }
    }

    private long writeObjects(Path path) throws Exception {
        long[] count = {0L};
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            jdbcTemplate.query("""
                    SELECT l.id, l.seller_id, c.code AS category_code, l.title, l.author,
                           l.description, l.unit_price, l.currency, l.status, l.published_at,
                           s.available, l.updated_at
                    FROM book_listing l
                    JOIN book_category c ON c.id = l.category_id
                    JOIN sku_stock s ON s.listing_id = l.id
                    ORDER BY l.id
                    """, resultSet -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("object_id", Long.toString(resultSet.getLong("id")));
                row.put("seller_key", hash(Long.toString(resultSet.getLong("seller_id"))));
                row.put("category_code", resultSet.getString("category_code"));
                row.put("title", resultSet.getString("title"));
                row.put("author", resultSet.getString("author"));
                row.put("description", resultSet.getString("description"));
                row.put("price", resultSet.getBigDecimal("unit_price"));
                row.put("currency", resultSet.getString("currency"));
                row.put("status", resultSet.getString("status"));
                row.put("published_at", resultSet.getTimestamp("published_at").toInstant());
                row.put("available", resultSet.getInt("available"));
                row.put("updated_at", resultSet.getTimestamp("updated_at").toInstant());
                row.put("data_source", "REAL");
                writeLine(writer, row);
                count[0]++;
            });
        }
        return count[0];
    }

    private long writeEvents(Path path, long watermark) throws Exception {
        long[] count = {0L};
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            jdbcTemplate.query("""
                    SELECT event_id, user_id, listing_id, action, event_time, ingested_at,
                           request_id, session_id, position, source, model_version, order_no,
                           refund_reason_code, schema_version, data_source
                    FROM trade_behavior_event
                    WHERE id <= ?
                    ORDER BY event_time, id
                    """, resultSet -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("event_id", resultSet.getString("event_id"));
                row.put("user_key", hash(Long.toString(resultSet.getLong("user_id"))));
                row.put("object_id", Long.toString(resultSet.getLong("listing_id")));
                row.put("event_type", resultSet.getString("action"));
                row.put("event_time", resultSet.getTimestamp("event_time").toInstant());
                row.put("ingested_at", resultSet.getTimestamp("ingested_at").toInstant());
                row.put("request_id", resultSet.getString("request_id"));
                row.put("session_id", resultSet.getString("session_id"));
                Object position = resultSet.getObject("position");
                row.put("position", position);
                row.put("source", resultSet.getString("source"));
                row.put("model_version", resultSet.getString("model_version"));
                row.put("order_no", resultSet.getString("order_no"));
                row.put("refund_reason_code", resultSet.getString("refund_reason_code"));
                row.put("schema_version", resultSet.getInt("schema_version"));
                row.put("data_source", resultSet.getString("data_source"));
                writeLine(writer, row);
                count[0]++;
            }, watermark);
        }
        return count[0];
    }

    private String hash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("运行环境不支持 HmacSHA256", exception);
        }
    }

    private void writeLine(BufferedWriter writer, Map<String, Object> row) {
        try {
            writer.write(objectMapper.writeValueAsString(row));
            writer.newLine();
        } catch (Exception exception) {
            throw new IllegalStateException("写入推荐快照中间行失败", exception);
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int size;
            while ((size = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, size);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
