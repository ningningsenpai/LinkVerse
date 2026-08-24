package ning.linkverse.payment.application;

import ning.linkverse.payment.domain.PaymentIntent;
import ning.linkverse.payment.domain.PaymentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PaymentExpirationServiceTest 验证扫描任务只关闭已经到期且仍为 PENDING 的 Intent。
 *
 * @author ning
 * @date 2026-08-24
 */
class PaymentExpirationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T08:00:00Z");

    @Test
    void shouldCloseOnlyExpiredPendingIntents() {
        PaymentRepository repository = mock(PaymentRepository.class);
        PaymentTransactionService transactionService = mock(PaymentTransactionService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PaymentIntent expiredPending = intent("pending-expired", "PENDING", NOW.minusSeconds(1));
        PaymentIntent futurePending = intent("pending-future", "PENDING", NOW.plusSeconds(1));
        PaymentIntent expiredSucceeded = intent("succeeded-expired", "SUCCEEDED", NOW.minusSeconds(1));
        when(repository.findDuePending(NOW, 50))
                .thenReturn(List.of(expiredPending, futurePending, expiredSucceeded));
        PaymentExpirationService service = new PaymentExpirationService(repository, transactionService, clock);

        service.closeDueIntents();

        verify(transactionService).closePending(expiredPending, NOW);
        verify(transactionService, never()).closePending(futurePending, NOW);
        verify(transactionService, never()).closePending(expiredSucceeded, NOW);
        verify(repository).findDuePending(NOW, 50);
    }

    private PaymentIntent intent(String orderNo, String status, Instant expireAt) {
        return new PaymentIntent(
                1L,
                "intent-" + orderNo,
                orderNo,
                10001L,
                90001L,
                new BigDecimal("68.0000"),
                "CNY",
                "MOCK",
                status,
                null,
                expireAt,
                null,
                null,
                null,
                NOW.minusSeconds(60)
        );
    }
}
