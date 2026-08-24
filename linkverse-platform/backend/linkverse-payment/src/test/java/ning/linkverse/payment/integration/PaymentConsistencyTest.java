package ning.linkverse.payment.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ning.linkverse.core.error.PlatformException;
import ning.linkverse.payment.application.MockHmacVerifier;
import ning.linkverse.payment.application.MockPaymentCallbackService;
import ning.linkverse.payment.application.PaymentApplicationService;
import ning.linkverse.payment.application.PaymentTransactionService;
import ning.linkverse.payment.domain.CreatePaymentIntent;
import ning.linkverse.payment.domain.PaymentCallback;
import ning.linkverse.payment.domain.PaymentErrorCode;
import ning.linkverse.payment.domain.PaymentIntent;
import ning.linkverse.payment.infrastructure.persistence.JdbcPaymentRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PaymentConsistencyTest 在真实 MySQL 上验证 Intent、回调和关单竞态收敛。
 *
 * @author ning
 * @date 2026-08-24
 */
@Testcontainers(disabledWithoutDocker = false)
class PaymentConsistencyTest {

    private static final Instant NOW = Instant.parse("2026-08-24T08:00:00Z");
    private static final String SECRET = "payment-test-hmac-secret-32-bytes-minimum";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("linkverse_mvp_payment")
            .withUsername("payment_test")
            .withPassword("payment_test_password");

    private static JdbcTemplate jdbcTemplate;
    private static TransactionTemplate transactions;
    private static HikariDataSource dataSource;

    private JdbcPaymentRepository repository;
    private PaymentApplicationService paymentService;
    private MockPaymentCallbackService callbackService;
    private MockHmacVerifier verifier;
    private ObjectMapper objectMapper;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(MYSQL.getJdbcUrl());
        hikariConfig.setUsername(MYSQL.getUsername());
        hikariConfig.setPassword(MYSQL.getPassword());
        hikariConfig.setMaximumPoolSize(20);
        hikariConfig.setPoolName("payment-consistency-test");
        dataSource = new HikariDataSource(hikariConfig);
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void closeDataSource() {
        dataSource.close();
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM refund_attempt");
        jdbcTemplate.update("DELETE FROM payment_exception");
        jdbcTemplate.update("DELETE FROM payment_callback_log");
        jdbcTemplate.update("DELETE FROM payment_intent");
        repository = new JdbcPaymentRepository(jdbcTemplate);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PaymentTransactionService transactionService = transactionalWriteService(repository, objectMapper);
        paymentService = new PaymentApplicationService(repository, transactionService, clock);
        verifier = new MockHmacVerifier(SECRET, Duration.ofMinutes(5), clock);
        callbackService = transactionalCallbackService(repository, verifier, objectMapper, clock);
    }

    @Test
    void shouldCreateOneIntentForOneHundredConcurrentRequests() throws Exception {
        CreatePaymentIntent command = command(compactUuid());
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CountDownLatch ready = new CountDownLatch(100);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<PaymentIntent>> futures = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return paymentService.create(command);
                }));
            }
            ready.await();
            start.countDown();
            HashSet<String> intentNumbers = new HashSet<>();
            for (Future<PaymentIntent> future : futures) {
                intentNumbers.add(future.get().intentNo());
            }

            assertThat(intentNumbers).hasSize(1);
            assertThat(count("payment_intent")).isEqualTo(1);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void shouldApplyOneTransitionForOneHundredCallbackReplays() throws Exception {
        PaymentIntent intent = paymentService.create(command(compactUuid()));
        SignedCallback signed = signed(intent, "replay-100");

        for (int index = 0; index < 100; index++) {
            assertThat(callbackService.accept(signed.timestamp(), signed.signature(), signed.rawBody()).status())
                    .isEqualTo("SUCCEEDED");
        }

        assertThat(count("payment_callback_log")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(repository.findByIntentNo(intent.intentNo()).orElseThrow().status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void shouldQueryAndReplayParkedOutboxWithoutExposingPayload() {
        String eventId = compactUuid();
        jdbcTemplate.update("""
                        INSERT INTO outbox_event (
                            event_id, aggregate_id, event_type, exchange_name, routing_key,
                            payload_json, status, attempt_count, next_attempt_at, locked_until,
                            last_error_digest, created_at
                        ) VALUES (?, ?, 'PaymentSucceeded', 'linkverse.events', 'payment.fact',
                                  CAST(? AS JSON), 'PARKED', 5, ?, ?, ?, ?)
                        """,
                eventId, compactUuid(), "{\"secret\":\"不得返回\"}", NOW, NOW,
                "a".repeat(64), NOW);

        var parked = repository.findByEventId(eventId).orElseThrow();
        assertThat(parked.status()).isEqualTo("PARKED");
        assertThat(parked.attemptCount()).isEqualTo(5);
        assertThat(repository.replayParked(eventId, NOW.plusSeconds(1))).isTrue();
        assertThat(repository.replayParked(eventId, NOW.plusSeconds(2))).isFalse();

        var pending = repository.findByEventId(eventId).orElseThrow();
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.attemptCount()).isZero();
        assertThat(pending.lastErrorDigest()).isNull();
    }

    @Test
    void shouldRejectForgedSignatureAndWrongBusinessFields() throws Exception {
        PaymentIntent intent = paymentService.create(command(compactUuid()));
        SignedCallback valid = signed(intent, "valid-base");

        assertThatThrownBy(() -> callbackService.accept(valid.timestamp(), "00", valid.rawBody()))
                .isInstanceOf(PlatformException.class)
                .satisfies(exception -> assertThat(((PlatformException) exception).errorCode())
                        .isEqualTo(PaymentErrorCode.CALLBACK_SIGNATURE_INVALID));

        List<PaymentCallback> invalidCallbacks = List.of(
                callback(intent, "wrong-merchant", intent.merchantId() + 1, intent.amount(), intent.currency()),
                callback(intent, "wrong-amount", intent.merchantId(), intent.amount().add(BigDecimal.ONE), intent.currency()),
                callback(intent, "wrong-currency", intent.merchantId(), intent.amount(), "USD")
        );
        for (PaymentCallback callback : invalidCallbacks) {
            byte[] raw = objectMapper.writeValueAsBytes(callback);
            String timestamp = Long.toString(NOW.getEpochSecond());
            String signature = verifier.sign(timestamp, raw);
            assertThatThrownBy(() -> callbackService.accept(timestamp, signature, raw))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(exception -> assertThat(((PlatformException) exception).errorCode())
                            .isEqualTo(PaymentErrorCode.CALLBACK_MISMATCH));
        }
        assertThat(count("payment_callback_log")).isZero();
    }

    @Test
    void shouldConvergeCreateAndCloseRaceForOneThousandRounds() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (int index = 0; index < 1000; index++) {
                CreatePaymentIntent command = command(compactUuid());
                CountDownLatch start = new CountDownLatch(1);
                Future<?> create = executor.submit(() -> awaitThen(start, () -> paymentService.create(command)));
                Future<?> close = executor.submit(() -> awaitThen(start, () -> paymentService.close(command)));
                start.countDown();
                create.get();
                close.get();
                assertThat(repository.findByOrderNo(command.orderNo()).orElseThrow().status())
                        .isEqualTo("CLOSED");
            }
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void shouldConvergeSuccessAndCloseRaceForOneThousandRounds() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (int index = 0; index < 1000; index++) {
                CreatePaymentIntent command = command(compactUuid());
                PaymentIntent intent = paymentService.create(command);
                SignedCallback signed = signed(intent, "race-" + index);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> success = executor.submit(() -> awaitThen(start, () -> callbackService.accept(
                        signed.timestamp(), signed.signature(), signed.rawBody())));
                Future<?> close = executor.submit(() -> awaitThen(start, () -> paymentService.close(command)));
                start.countDown();
                success.get();
                close.get();
                assertThat(repository.findByIntentNo(intent.intentNo()).orElseThrow().status())
                        .isIn("SUCCEEDED", "REFUNDED");
            }
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void shouldRefundLateSuccessWithoutReopeningClosedIntent() throws Exception {
        CreatePaymentIntent command = command(compactUuid());
        PaymentIntent intent = paymentService.create(command);
        assertThat(paymentService.close(command).status()).isEqualTo("CLOSED");
        SignedCallback signed = signed(intent, "late-success");

        PaymentIntent refunded = callbackService.accept(
                signed.timestamp(),
                signed.signature(),
                signed.rawBody()
        );

        assertThat(refunded.status()).isEqualTo("REFUNDED");
        assertThat(count("refund_attempt")).isEqualTo(1);
        assertThat(count("payment_exception")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT refund_request_no FROM refund_attempt WHERE intent_no = ?",
                String.class,
                intent.intentNo()
        )).isEqualTo("refund:" + intent.intentNo());
    }

    private PaymentTransactionService transactionalWriteService(
            JdbcPaymentRepository repository,
            ObjectMapper mapper
    ) {
        return new PaymentTransactionService(repository, mapper) {
            @Override
            public void insertPending(CreatePaymentIntent command, String intentNo, Instant now) {
                transactions.executeWithoutResult(status -> super.insertPending(command, intentNo, now));
            }

            @Override
            public PaymentIntent insertClosed(CreatePaymentIntent command, String intentNo, Instant now) {
                return transactions.execute(status -> super.insertClosed(command, intentNo, now));
            }

            @Override
            public boolean closePending(PaymentIntent intent, Instant now) {
                return Boolean.TRUE.equals(transactions.execute(status -> super.closePending(intent, now)));
            }
        };
    }

    private MockPaymentCallbackService transactionalCallbackService(
            JdbcPaymentRepository repository,
            MockHmacVerifier verifier,
            ObjectMapper mapper,
            Clock clock
    ) {
        return new MockPaymentCallbackService(repository, verifier, mapper, clock) {
            @Override
            public PaymentIntent accept(String timestamp, String signature, byte[] rawBody) {
                return transactions.execute(status -> super.accept(timestamp, signature, rawBody));
            }
        };
    }

    private SignedCallback signed(PaymentIntent intent, String key) throws Exception {
        PaymentCallback callback = callback(
                intent,
                key,
                intent.merchantId(),
                intent.amount(),
                intent.currency()
        );
        byte[] raw = objectMapper.writeValueAsBytes(callback);
        String timestamp = Long.toString(NOW.getEpochSecond());
        return new SignedCallback(timestamp, verifier.sign(timestamp, raw), raw);
    }

    private PaymentCallback callback(
            PaymentIntent intent,
            String key,
            long merchantId,
            BigDecimal amount,
            String currency
    ) {
        String stable = digestKey(key);
        return new PaymentCallback(
                "n_" + stable,
                "t_" + stable,
                intent.intentNo(),
                intent.orderNo(),
                merchantId,
                amount,
                currency,
                "SUCCEEDED"
        );
    }

    private CreatePaymentIntent command(String orderNo) {
        return new CreatePaymentIntent(
                orderNo,
                10001L,
                90001L,
                new BigDecimal("68.0000"),
                "CNY",
                NOW.plusSeconds(900)
        );
    }

    private void awaitThen(CountDownLatch start, Runnable action) {
        try {
            start.await();
            action.run();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("支付竞态测试被中断", exception);
        }
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String digestKey(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes()).toString().replace("-", "");
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record SignedCallback(String timestamp, String signature, byte[] rawBody) {
    }
}
