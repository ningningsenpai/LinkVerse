package ning.linkverse.messaging.outbox;

/**
 * OutboxStats 提供固定低基数的积压、停车和最老消息年龄指标。
 *
 * @author ning
 * @date 2026-08-24
 */
public record OutboxStats(long pending, long parked, long oldestPendingSeconds) {

    public static OutboxStats empty() {
        return new OutboxStats(0, 0, 0);
    }
}
