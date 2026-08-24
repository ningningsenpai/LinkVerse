package ning.linkverse.messaging.outbox;

import java.time.Instant;

/**
 * OutboxMessage 表示已由业务库抢占、等待可靠发布的一条事件。
 *
 * @author ning
 * @date 2026-08-24
 */
public record OutboxMessage(
        long id,
        String eventId,
        String exchange,
        String routingKey,
        String payloadJson,
        int attemptCount,
        Instant createdAt
) {
}
