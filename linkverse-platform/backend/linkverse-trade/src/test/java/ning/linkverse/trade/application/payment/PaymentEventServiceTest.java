package ning.linkverse.trade.application.payment;

import ning.linkverse.trade.domain.TradeRepository;
import ning.linkverse.trade.domain.order.PayableOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PaymentEventServiceTest 验证支付事实的消费去重和订单快照校验。
 *
 * @author ning
 * @date 2026-08-24
 */
class PaymentEventServiceTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-24T08:00:00Z");

    @Test
    void shouldApplyOneTransitionForOneHundredDeliveries() {
        TradeRepository repository = mock(TradeRepository.class);
        AtomicBoolean first = new AtomicBoolean(true);
        when(repository.recordConsumedEvent(anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> first.getAndSet(false));
        when(repository.findPayableByOrder("order-1")).thenReturn(Optional.of(order("PENDING_PAYMENT")));
        when(repository.markPaid("order-1", "intent-1", OCCURRED_AT)).thenReturn(true);
        PaymentEventService service = new PaymentEventService(repository);

        for (int index = 0; index < 100; index++) {
            service.acceptSucceeded(
                    "event-1",
                    "intent-1",
                    "order-1",
                    new BigDecimal("68.0000"),
                    "CNY",
                    OCCURRED_AT
            );
        }

        verify(repository, times(100)).recordConsumedEvent(anyString(), anyString(), anyString(), any());
        verify(repository, times(1)).markPaid("order-1", "intent-1", OCCURRED_AT);
    }

    @Test
    void shouldRejectAmountMismatchBeforeChangingOrder() {
        TradeRepository repository = mock(TradeRepository.class);
        when(repository.recordConsumedEvent(anyString(), anyString(), anyString(), any())).thenReturn(true);
        when(repository.findPayableByOrder("order-1")).thenReturn(Optional.of(order("PENDING_PAYMENT")));
        PaymentEventService service = new PaymentEventService(repository);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.acceptSucceeded(
                "event-2",
                "intent-1",
                "order-1",
                new BigDecimal("69.0000"),
                "CNY",
                OCCURRED_AT
        ));

        assertEquals("支付事件金额或币种与订单快照不一致", exception.getMessage());
        verify(repository, never()).markPaid(anyString(), anyString(), any());
    }

    private PayableOrder order(String status) {
        return new PayableOrder(
                "order-1",
                10001L,
                90001L,
                new BigDecimal("68.0000"),
                "CNY",
                status,
                OCCURRED_AT.plusSeconds(900),
                null
        );
    }
}
