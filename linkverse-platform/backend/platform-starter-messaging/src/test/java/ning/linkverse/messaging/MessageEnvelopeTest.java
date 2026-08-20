package ning.linkverse.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MessageEnvelopeTest 验证消息信封的 snake_case 契约与失败边界。
 *
 * @author ning
 * @date 2026-08-19
 */
class MessageEnvelopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void shouldSerializeStableSnakeCaseEnvelope() throws Exception {
        MessageEnvelope<Map<String, String>> envelope = new MessageEnvelope<>(
                "event-1",
                "PaymentSucceeded",
                1,
                "intent-1",
                Instant.parse("2026-08-19T00:00:00Z"),
                "0123456789abcdef0123456789abcdef",
                Map.of("order_no", "order-1")
        );

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(envelope));

        assertEquals("event-1", json.get("event_id").asText());
        assertEquals("PaymentSucceeded", json.get("event_type").asText());
        assertEquals(1, json.get("schema_version").asInt());
        assertEquals("intent-1", json.get("aggregate_id").asText());
        assertEquals("0123456789abcdef0123456789abcdef", json.get("trace_id").asText());
        assertTrue(json.has("occurred_at"));
        assertFalse(json.has("eventId"));
    }

    @Test
    void shouldRejectMissingStableEventId() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new MessageEnvelope<>(
                        " ",
                        "PaymentSucceeded",
                        1,
                        "intent-1",
                        Instant.parse("2026-08-19T00:00:00Z"),
                        "trace-1",
                        Map.of()
                )
        );

        assertEquals("事件 ID 不能为空", exception.getMessage());
    }
}
