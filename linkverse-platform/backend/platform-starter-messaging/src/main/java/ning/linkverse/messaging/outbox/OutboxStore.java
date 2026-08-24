package ning.linkverse.messaging.outbox;

import java.time.Instant;
import java.util.List;

/**
 * OutboxStore 隔离 Relay 与各服务的本地表实现，事务抢占由业务服务负责。
 *
 * @author ning
 * @date 2026-08-24
 */
public interface OutboxStore {

    List<OutboxMessage> claim(Instant now, Instant lockedUntil, int limit);

    void markPublished(long id, Instant publishedAt);

    void markFailed(long id, int attempts, Instant nextAttemptAt, String errorDigest, boolean parked);
}
