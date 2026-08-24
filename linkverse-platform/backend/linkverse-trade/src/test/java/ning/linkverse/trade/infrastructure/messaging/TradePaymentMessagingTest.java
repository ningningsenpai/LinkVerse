package ning.linkverse.trade.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ning.linkverse.messaging.MessageEnvelope;
import ning.linkverse.trade.application.closing.OrderClosingService;
import ning.linkverse.trade.application.payment.PaymentEventService;
import ning.linkverse.trade.domain.TradeRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradePaymentMessagingTest 验证 PaymentClosed 只有完成订单收敛后才记录消费事实。
 *
 * @author ning
 * @date 2026-08-24
 */
class TradePaymentMessagingTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-24T08:00:00Z");

    @Test
    void shouldRecordConsumedEventOnlyAfterPaymentClosedRecoverySucceeds() throws Exception {
        TradeRepository repository = mock(TradeRepository.class);
        PaymentEventService eventService = new PaymentEventService(repository);
        OrderClosingService closingService = mock(OrderClosingService.class);
        when(closingService.reconcile("order-1")).thenReturn(false, true);
        TradePaymentMessaging.Listener listener = new TradePaymentMessaging.Listener(
                objectMapper(),
                eventService,
                closingService
        );
        byte[] rawBody = paymentClosedEvent();

        assertThatThrownBy(() -> listener.consume(rawBody))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("支付关闭事件尚未完成订单收敛");
        verify(repository, never()).recordConsumedEvent(
                any(), any(), eq("PaymentClosed"), any());

        when(repository.recordConsumedEvent(
                "trade-payment-fact-v1",
                "event-1",
                "PaymentClosed",
                OCCURRED_AT
        )).thenReturn(true);
        listener.consume(rawBody);

        verify(repository).recordConsumedEvent(
                "trade-payment-fact-v1",
                "event-1",
                "PaymentClosed",
                OCCURRED_AT
        );
    }

    private byte[] paymentClosedEvent() throws Exception {
        MessageEnvelope<Map<String, String>> envelope = new MessageEnvelope<>(
                "event-1",
                "PaymentClosed",
                1,
                "order-1",
                OCCURRED_AT,
                "trace-1",
                Map.of("order_no", "order-1")
        );
        return objectMapper().writeValueAsBytes(envelope);
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
