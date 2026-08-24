package ning.linkverse.payment.application;

import ning.linkverse.core.error.PlatformException;
import ning.linkverse.payment.domain.CreatePaymentIntent;
import ning.linkverse.payment.domain.PaymentErrorCode;
import ning.linkverse.payment.domain.PaymentIntent;
import ning.linkverse.payment.domain.PaymentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PaymentApplicationServiceTest 验证创建和关单对过期支付窗口的不同处理。
 *
 * @author ning
 * @date 2026-08-24
 */
class PaymentApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T08:00:00Z");
    private static final String ORDER_NO = "0123456789abcdef0123456789abcdef";

    @Test
    void shouldRejectExpiredIntentCreationBeforeWriting() {
        PaymentRepository repository = mock(PaymentRepository.class);
        PaymentTransactionService transactionService = mock(PaymentTransactionService.class);
        PaymentApplicationService service = service(repository, transactionService);

        assertThatThrownBy(() -> service.create(command(NOW.minusSeconds(1))))
                .isInstanceOf(PlatformException.class)
                .satisfies(exception -> assertThat(((PlatformException) exception).errorCode())
                        .isEqualTo(PaymentErrorCode.INTENT_EXPIRED));

        verify(repository, never()).findByOrderNo(ORDER_NO);
        verify(transactionService, never()).insertPending(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void shouldAllowClosingAnAlreadyExpiredPendingIntent() {
        PaymentRepository repository = mock(PaymentRepository.class);
        PaymentTransactionService transactionService = mock(PaymentTransactionService.class);
        CreatePaymentIntent command = command(NOW.minusSeconds(1));
        PaymentIntent pending = intent("PENDING", command.expireAt());
        PaymentIntent closed = intent("CLOSED", command.expireAt());
        when(repository.findByOrderNo(ORDER_NO)).thenReturn(Optional.of(pending), Optional.of(closed));
        when(transactionService.closePending(pending, NOW)).thenReturn(true);
        PaymentApplicationService service = service(repository, transactionService);

        PaymentIntent result = service.close(command);

        assertThat(result.status()).isEqualTo("CLOSED");
        verify(transactionService).closePending(pending, NOW);
    }

    private PaymentApplicationService service(
            PaymentRepository repository,
            PaymentTransactionService transactionService
    ) {
        return new PaymentApplicationService(
                repository,
                transactionService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private CreatePaymentIntent command(Instant expireAt) {
        return new CreatePaymentIntent(
                ORDER_NO,
                10001L,
                90001L,
                new BigDecimal("68.0000"),
                "CNY",
                expireAt
        );
    }

    private PaymentIntent intent(String status, Instant expireAt) {
        return new PaymentIntent(
                1L,
                "0123456789abcdef0123456789abcdef",
                ORDER_NO,
                10001L,
                90001L,
                new BigDecimal("68.0000"),
                "CNY",
                "MOCK",
                status,
                null,
                expireAt,
                null,
                "CLOSED".equals(status) ? NOW : null,
                null,
                NOW.minusSeconds(60)
        );
    }
}
