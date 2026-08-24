package ning.linkverse.messaging.outbox;

import java.time.Instant;

/**
 * OutboxEventView 是运维查询与人工重放使用的脱敏事件摘要。
 *
 * @author ning
 * @date 2026-08-24
 */
public record OutboxEventView(
        String eventId,
        String aggregateId,
        String eventType,
        String status,
        int attemptCount,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant publishedAt,
        String lastErrorDigest
) {
}
