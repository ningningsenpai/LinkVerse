package ning.linkverse.messaging.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OutboxRelayTest 验证发布确认、有限重试和停车判定。
 *
 * @author ning
 * @date 2026-08-24
 */
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-08-24T08:00:00Z");

    @Test
    void shouldMarkPublishedOnlyAfterBrokerAck() {
        RecordingStore store = new RecordingStore(message(0));
        AcknowledgingRabbitTemplate rabbitTemplate = new AcknowledgingRabbitTemplate(true);

        int count = new OutboxRelay(store, rabbitTemplate, fixedClock()).relayOnce(10);

        assertEquals(1, count);
        assertEquals(1L, store.publishedId);
        assertEquals("event-1", rabbitTemplate.sent.getMessageProperties().getMessageId());
        assertEquals("event-1", rabbitTemplate.sent.getMessageProperties().getHeader("event_id"));
        assertEquals(NOW, store.publishedAt);
    }

    @Test
    void shouldScheduleRetryWhenBrokerRejectsMessage() {
        RecordingStore store = new RecordingStore(message(0));

        new OutboxRelay(store, new AcknowledgingRabbitTemplate(false), fixedClock()).relayOnce(10);

        assertEquals(1, store.failedAttempts);
        assertEquals(NOW.plusSeconds(2), store.nextAttemptAt);
        assertFalse(store.parked);
        assertNotNull(store.errorDigest);
        assertEquals(64, store.errorDigest.length());
    }

    @Test
    void shouldParkMessageAfterFifthFailure() {
        RecordingStore store = new RecordingStore(message(4));
        AcknowledgingRabbitTemplate rabbitTemplate = new AcknowledgingRabbitTemplate(false);

        new OutboxRelay(store, rabbitTemplate, fixedClock()).relayOnce(10);

        assertEquals(5, store.failedAttempts);
        assertTrue(store.parked);
        assertEquals("linkverse.events.parking", rabbitTemplate.parkingExchange);
        assertEquals("payment.fact", rabbitTemplate.parkingRoutingKey);
        assertTrue(Boolean.TRUE.equals(rabbitTemplate.parked.getMessageProperties().getHeader("parked")));
    }

    @Test
    void shouldExposeBoundedOutboxMetrics() {
        RecordingStore store = new RecordingStore(message(0));
        store.stats = new OutboxStats(2, 1, 30);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxRelay(store, new AcknowledgingRabbitTemplate(true), fixedClock(), registry)
                .relayOnce(10);

        assertEquals(1.0, registry.get("linkverse.outbox.publish")
                .tag("result", "published").counter().count());
        assertEquals(2.0, registry.get("linkverse.outbox.pending").gauge().value());
        assertEquals(1.0, registry.get("linkverse.outbox.parked").gauge().value());
        assertEquals(30.0, registry.get("linkverse.outbox.oldest.seconds").gauge().value());
    }

    @Test
    void shouldRetryAcknowledgedButUnroutableMessage() {
        RecordingStore store = new RecordingStore(message(0));
        AcknowledgingRabbitTemplate rabbitTemplate = new AcknowledgingRabbitTemplate(true, true);

        new OutboxRelay(store, rabbitTemplate, fixedClock()).relayOnce(10);

        assertEquals(0L, store.publishedId);
        assertEquals(1, store.failedAttempts);
        assertFalse(store.parked);
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private OutboxMessage message(int attemptCount) {
        return new OutboxMessage(
                1L,
                "event-1",
                "linkverse.events",
                "payment.fact",
                "{\"event_id\":\"event-1\"}",
                attemptCount,
                NOW
        );
    }

    private static final class AcknowledgingRabbitTemplate extends RabbitTemplate {

        private final boolean acknowledged;
        private final boolean returned;
        private Message sent;
        private Message parked;
        private String parkingExchange;
        private String parkingRoutingKey;

        private AcknowledgingRabbitTemplate(boolean acknowledged) {
            this(acknowledged, false);
        }

        private AcknowledgingRabbitTemplate(boolean acknowledged, boolean returned) {
            this.acknowledged = acknowledged;
            this.returned = returned;
        }

        @Override
        public void send(
                String exchange,
                String routingKey,
                Message message,
                CorrelationData correlationData
        ) {
            sent = message;
            if (returned) {
                correlationData.setReturned(new ReturnedMessage(
                        message,
                        312,
                        "NO_ROUTE",
                        exchange,
                        routingKey
                ));
            }
            correlationData.getFuture().complete(new CorrelationData.Confirm(
                    acknowledged,
                    acknowledged ? null : "模拟 Broker 拒绝"
            ));
        }

        @Override
        public void send(String exchange, String routingKey, Message message) {
            parkingExchange = exchange;
            parkingRoutingKey = routingKey;
            parked = message;
        }
    }

    private static final class RecordingStore implements OutboxStore {

        private final OutboxMessage message;
        private long publishedId;
        private Instant publishedAt;
        private int failedAttempts;
        private Instant nextAttemptAt;
        private String errorDigest;
        private boolean parked;
        private OutboxStats stats = OutboxStats.empty();

        private RecordingStore(OutboxMessage message) {
            this.message = message;
        }

        @Override
        public List<OutboxMessage> claim(Instant now, Instant leaseUntil, int limit) {
            return List.of(message);
        }

        @Override
        public void markPublished(long id, Instant publishedAt) {
            this.publishedId = id;
            this.publishedAt = publishedAt;
        }

        @Override
        public void markFailed(
                long id,
                int attemptCount,
                Instant nextAttemptAt,
                String errorDigest,
                boolean parked
        ) {
            this.failedAttempts = attemptCount;
            this.nextAttemptAt = nextAttemptAt;
            this.errorDigest = errorDigest;
            this.parked = parked;
        }

        @Override
        public OutboxStats stats(Instant now) {
            return stats;
        }
    }
}
