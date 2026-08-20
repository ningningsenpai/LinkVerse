package ning.linkverse.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

/**
 * MessageEnvelope 定义跨服务事件的最小版本化信封，事件 ID 必须由业务侧稳定生成。
 *
 * @param eventId       全局唯一事件 ID
 * @param eventType     稳定事件类型
 * @param schemaVersion 信封内业务载荷版本
 * @param aggregateId   事件所属聚合标识
 * @param occurredAt    业务事实发生时间
 * @param traceId       产生事件的 Trace ID
 * @param payload       最小业务快照
 * @author ning
 * @date 2026-08-19
 */
public record MessageEnvelope<T>(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("aggregate_id") String aggregateId,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("payload") T payload
) {

    public MessageEnvelope {
        eventId = requireText(eventId, "事件 ID 不能为空");
        eventType = requireText(eventType, "事件类型不能为空");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("事件 Schema 版本必须大于零");
        }
        aggregateId = requireText(aggregateId, "聚合标识不能为空");
        occurredAt = Objects.requireNonNull(occurredAt, "事件发生时间不能为空");
        traceId = requireText(traceId, "Trace ID 不能为空");
        payload = Objects.requireNonNull(payload, "事件载荷不能为空");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
