package ning.linkverse.trade.application.order;

import ning.linkverse.core.error.PlatformException;
import ning.linkverse.trade.domain.TradeErrorCode;
import ning.linkverse.trade.domain.TradeRepository;
import ning.linkverse.trade.domain.order.OrderItemSnapshot;
import ning.linkverse.trade.domain.order.TradeOrder;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OrderApplicationServiceTest 验证普通下单的幂等恢复和订单所有者边界。
 *
 * @author ning
 * @date 2026-08-24
 */
class OrderApplicationServiceTest {

    private static final long BUYER_ID = 1001L;
    private static final long LISTING_ID = 2001L;
    private static final String IDEMPOTENCY_KEY = "order-test-key-0001";

    @Test
    void shouldReplayCommittedOrderWithoutCreatingAgain() {
        TradeRepository repository = mock(TradeRepository.class);
        OrderTransactionService transactionService = mock(OrderTransactionService.class);
        TradeOrder committed = order(fingerprint(BUYER_ID, LISTING_ID, 1));
        when(repository.findByBuyerAndIdempotencyKey(BUYER_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(committed));
        OrderApplicationService service = new OrderApplicationService(repository, transactionService);

        OrderCreationResult result = service.create(BUYER_ID, LISTING_ID, 1, IDEMPOTENCY_KEY);

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isEqualTo(committed);
        verifyNoInteractions(transactionService);
    }

    @Test
    void shouldRejectSameKeyUsedForDifferentRequest() {
        TradeRepository repository = mock(TradeRepository.class);
        OrderTransactionService transactionService = mock(OrderTransactionService.class);
        when(repository.findByBuyerAndIdempotencyKey(BUYER_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(order(fingerprint(BUYER_ID, LISTING_ID, 1))));
        OrderApplicationService service = new OrderApplicationService(repository, transactionService);

        assertThatThrownBy(() -> service.create(BUYER_ID, LISTING_ID + 1, 1, IDEMPOTENCY_KEY))
                .isInstanceOf(PlatformException.class)
                .satisfies(exception -> assertThat(((PlatformException) exception).errorCode())
                        .isEqualTo(TradeErrorCode.IDEMPOTENCY_KEY_REUSED));
        verifyNoInteractions(transactionService);
    }

    @Test
    void shouldRecoverCommittedWinnerAfterConcurrentUniqueKeyConflict() {
        TradeRepository repository = mock(TradeRepository.class);
        OrderTransactionService transactionService = mock(OrderTransactionService.class);
        TradeOrder winner = order(fingerprint(BUYER_ID, LISTING_ID, 1));
        when(repository.findByBuyerAndIdempotencyKey(BUYER_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(transactionService.create(
                BUYER_ID,
                LISTING_ID,
                IDEMPOTENCY_KEY,
                winner.requestFingerprint(),
                "order-no-1"
        ))
                .thenThrow(new DuplicateKeyException("并发幂等键冲突"));
        OrderApplicationService service = new OrderApplicationService(
                repository,
                transactionService,
                () -> "order-no-1"
        );

        OrderCreationResult result = service.create(BUYER_ID, LISTING_ID, 1, IDEMPOTENCY_KEY);

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isEqualTo(winner);
    }

    @Test
    void shouldRetryOrderNumberCollisionWithBoundedNewNumber() {
        TradeRepository repository = mock(TradeRepository.class);
        OrderTransactionService transactionService = mock(OrderTransactionService.class);
        TradeOrder created = order(fingerprint(BUYER_ID, LISTING_ID, 1));
        when(repository.findByBuyerAndIdempotencyKey(BUYER_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(transactionService.create(
                BUYER_ID,
                LISTING_ID,
                IDEMPOTENCY_KEY,
                created.requestFingerprint(),
                "order-no-1"
        )).thenThrow(new DuplicateKeyException("订单号唯一键冲突"));
        when(transactionService.create(
                BUYER_ID,
                LISTING_ID,
                IDEMPOTENCY_KEY,
                created.requestFingerprint(),
                "order-no-2"
        )).thenReturn(created);
        AtomicInteger sequence = new AtomicInteger();
        OrderApplicationService service = new OrderApplicationService(
                repository,
                transactionService,
                () -> "order-no-" + sequence.incrementAndGet()
        );

        OrderCreationResult result = service.create(BUYER_ID, LISTING_ID, 1, IDEMPOTENCY_KEY);

        assertThat(result.created()).isTrue();
        assertThat(result.order()).isEqualTo(created);
        assertThat(sequence).hasValue(2);
    }

    @Test
    void shouldHideOrderFromNonOwner() {
        TradeRepository repository = mock(TradeRepository.class);
        when(repository.findByOrderNoAndBuyer("order-no", BUYER_ID)).thenReturn(Optional.empty());
        OrderApplicationService service = new OrderApplicationService(
                repository,
                mock(OrderTransactionService.class)
        );

        assertThatThrownBy(() -> service.findOwned("order-no", BUYER_ID))
                .isInstanceOf(PlatformException.class)
                .satisfies(exception -> assertThat(((PlatformException) exception).errorCode())
                        .isEqualTo(TradeErrorCode.ORDER_NOT_FOUND));
    }

    private static TradeOrder order(String requestFingerprint) {
        Instant now = Instant.parse("2026-08-24T06:00:00Z");
        BigDecimal price = new BigDecimal("39.9000");
        return new TradeOrder(
                1L,
                "a".repeat(32),
                BUYER_ID,
                9001L,
                IDEMPOTENCY_KEY,
                requestFingerprint,
                "PENDING_PAYMENT",
                price,
                "CNY",
                now.plusSeconds(900),
                now,
                new OrderItemSnapshot(LISTING_ID, 9001L, 0L, "测试商品", "测试作者", price, price, 1, "CNY")
        );
    }

    private static String fingerprint(long buyerId, long listingId, int quantity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    ("v1|" + buyerId + "|" + listingId + "|" + quantity)
                            .getBytes(StandardCharsets.UTF_8)
            ));
        } catch (Exception exception) {
            throw new AssertionError("测试环境不支持SHA-256", exception);
        }
    }
}
