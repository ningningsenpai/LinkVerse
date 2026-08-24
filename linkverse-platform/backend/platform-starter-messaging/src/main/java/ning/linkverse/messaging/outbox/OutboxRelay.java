package ning.linkverse.messaging.outbox;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * OutboxRelay 使用 RabbitMQ Confirm 发布已提交事件，并以有界退避保留失败记录。
 *
 * @author ning
 * @date 2026-08-24
 */
public final class OutboxRelay {

    private static final int MAX_ATTEMPTS = 5;

    private final OutboxStore store;
    private final RabbitTemplate rabbitTemplate;
    private final Clock clock;

    public OutboxRelay(OutboxStore store, RabbitTemplate rabbitTemplate, Clock clock) {
        this.store = store;
        this.rabbitTemplate = rabbitTemplate;
        this.clock = clock;
    }

    public int relayOnce(int batchSize) {
        Instant now = clock.instant();
        var messages = store.claim(now, now.plusSeconds(30), batchSize);
        for (OutboxMessage message : messages) {
            publish(message);
        }
        return messages.size();
    }

    private void publish(OutboxMessage outbox) {
        try {
            Message message = MessageBuilder
                    .withBody(outbox.payloadJson().getBytes(StandardCharsets.UTF_8))
                    .setContentType("application/json")
                    .setContentEncoding(StandardCharsets.UTF_8.name())
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setMessageId(outbox.eventId())
                    .setHeader("event_id", outbox.eventId())
                    .build();
            CorrelationData correlation = new CorrelationData(outbox.eventId());
            rabbitTemplate.send(outbox.exchange(), outbox.routingKey(), message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ 拒绝事件：" + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ 未路由事件："
                        + correlation.getReturned().getReplyText());
            }
            store.markPublished(outbox.id(), clock.instant());
        } catch (Exception exception) {
            int attempts = outbox.attemptCount() + 1;
            boolean parked = attempts >= MAX_ATTEMPTS;
            store.markFailed(
                    outbox.id(),
                    attempts,
                    clock.instant().plus(backoff(attempts)),
                    digest(exception.getClass().getName() + ":" + exception.getMessage()),
                    parked
            );
        }
    }

    private Duration backoff(int attempts) {
        return Duration.ofSeconds(Math.min(60, 1L << Math.min(attempts, 5)));
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("运行环境不支持SHA-256", exception);
        }
    }
}
