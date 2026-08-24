package ning.linkverse.trade.infrastructure.persistence;

import ning.linkverse.trade.application.order.OrderApplicationService;
import ning.linkverse.trade.application.order.OrderCreationResult;
import ning.linkverse.trade.application.order.OrderTransactionService;
import ning.linkverse.trade.domain.order.NewOrder;
import ning.linkverse.trade.domain.order.OrderItemSnapshot;
import ning.linkverse.trade.domain.order.TradeOrder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TradeMySqlRepositoryTest 在真实 MySQL 上验证条件扣库存、事务回滚和订单快照。
 *
 * @author ning
 * @date 2026-08-24
 */
@Testcontainers(disabledWithoutDocker = false)
class TradeMySqlRepositoryTest {

    private static final long LISTING_ID = 10001L;
    private static final long SELLER_ID = 90001L;
    private static final long BUYER_ID = 10001L;
    private static final Instant NOW = Instant.parse("2026-08-24T06:00:00Z");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("linkverse_mvp_trade")
            .withUsername("trade_test")
            .withPassword("trade_test_password");

    private static JdbcTemplate jdbcTemplate;
    private static TransactionTemplate transactionTemplate;

    private JdbcTradeRepository repository;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration", "classpath:db/local")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book_listing", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT SUM(available) FROM sku_stock", Integer.class))
                .isEqualTo(110);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM consumed_event");
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM trade_order");
        jdbcTemplate.update("DELETE FROM sku_stock");
        jdbcTemplate.update("DELETE FROM book_listing");
        insertListing(1, new BigDecimal("39.9000"), "初始标题", 3L);
        repository = new JdbcTradeRepository(jdbcTemplate);
    }

    @Test
    void shouldCommitOnlyOneOrderForConcurrentLastStock() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> first = executor.submit(() -> purchaseAfterSignal(
                    newOrder("1".repeat(32), "concurrent-key-0001", "fingerprint-concurrent-1"),
                    ready,
                    start
            ));
            Future<Boolean> second = executor.submit(() -> purchaseAfterSignal(
                    newOrder("2".repeat(32), "concurrent-key-0002", "fingerprint-concurrent-2"),
                    ready,
                    start
            ));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
            assertThat(stock()).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_item", Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldCreateOnlyOneOrderForOneHundredConcurrentSameKeys() throws Exception {
        jdbcTemplate.update("UPDATE sku_stock SET available = 100 WHERE listing_id = ?", LISTING_ID);
        OrderTransactionService transactionalService = new OrderTransactionService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15)
        ) {
            @Override
            public TradeOrder create(
                    long buyerId,
                    long listingId,
                    String idempotencyKey,
                    String fingerprint,
                    String orderNo
            ) {
                return transactionTemplate.execute(status -> super.create(
                        buyerId,
                        listingId,
                        idempotencyKey,
                        fingerprint,
                        orderNo
                ));
            }
        };
        OrderApplicationService service = new OrderApplicationService(repository, transactionalService);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CountDownLatch ready = new CountDownLatch(100);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<OrderCreationResult>> futures = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.create(BUYER_ID, LISTING_ID, 1, "same-key-concurrent-100");
                }));
            }
            ready.await();
            start.countDown();

            List<OrderCreationResult> results = new ArrayList<>();
            for (Future<OrderCreationResult> future : futures) {
                results.add(future.get());
            }
            assertThat(results).filteredOn(OrderCreationResult::created).hasSize(1);
            assertThat(new HashSet<>(results.stream()
                    .map(result -> result.order().orderNo())
                    .toList())).hasSize(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_item", Integer.class)).isEqualTo(1);
            assertThat(stock()).isEqualTo(99);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRollbackOrderAndStockTogether() {
        NewOrder order = newOrder("b".repeat(32), "rollback-key-0001", "fingerprint-rollback");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            long orderId = repository.insertOrder(order);
            assertThat(repository.decrementStock(LISTING_ID, NOW)).isTrue();
            repository.insertOrderItem(orderId, order);
            throw new IllegalStateException("主动触发事务回滚");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_item", Integer.class)).isZero();
        assertThat(stock()).isEqualTo(1);
    }

    @Test
    void shouldKeepOrderSnapshotAfterListingChanges() {
        NewOrder order = newOrder("c".repeat(32), "snapshot-key-0001", "fingerprint-snapshot");
        transactionTemplate.executeWithoutResult(status -> {
            long orderId = repository.insertOrder(order);
            assertThat(repository.decrementStock(LISTING_ID, NOW)).isTrue();
            repository.insertOrderItem(orderId, order);
        });

        jdbcTemplate.update(
                "UPDATE book_listing SET title = ?, unit_price = ?, version = version + 1 WHERE id = ?",
                "变更后的标题",
                new BigDecimal("99.0000"),
                LISTING_ID
        );
        TradeOrder restored = repository.findByOrderNoAndBuyer(order.orderNo(), BUYER_ID).orElseThrow();

        assertThat(restored.item().listingTitle()).isEqualTo("初始标题");
        assertThat(restored.item().unitPrice()).isEqualByComparingTo("39.9000");
        assertThat(restored.item().listingVersion()).isEqualTo(3L);
        assertThat(restored.totalAmount()).isEqualByComparingTo("39.9000");
    }

    @Test
    void shouldScanDuePendingOrdersInStableOrder() {
        NewOrder first = newOrder("d".repeat(32), "due-key-00000001", "fingerprint-due-1");
        NewOrder second = newOrder("e".repeat(32), "due-key-00000002", "fingerprint-due-2");
        transactionTemplate.executeWithoutResult(status -> {
            insertCompleteOrder(first);
            insertCompleteOrder(second);
        });

        assertThat(repository.findDuePending(NOW.plusSeconds(901), null, null, 10))
                .extracting(candidate -> candidate.orderNo())
                .containsExactly(first.orderNo(), second.orderNo());
    }

    @Test
    void shouldRecoverClosingOrderAfterLeaseTimeout() {
        NewOrder order = newOrder("f".repeat(32), "closing-key-0001", "fingerprint-closing");
        transactionTemplate.executeWithoutResult(status -> insertCompleteOrder(order));
        jdbcTemplate.update(
                "UPDATE trade_order SET status = 'CLOSING', updated_at = ? WHERE order_no = ?",
                NOW.minusSeconds(31),
                order.orderNo()
        );

        assertThat(repository.findDuePending(NOW, null, null, 10))
                .extracting(candidate -> candidate.orderNo())
                .containsExactly(order.orderNo());
    }

    private boolean purchaseAfterSignal(
            NewOrder order,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            long orderId = repository.insertOrder(order);
            ready.countDown();
            try {
                start.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并发购买测试被中断", exception);
            }
            if (!repository.decrementStock(LISTING_ID, NOW)) {
                status.setRollbackOnly();
                return false;
            }
            repository.insertOrderItem(orderId, order);
            return true;
        }));
    }

    private void insertCompleteOrder(NewOrder order) {
        long orderId = repository.insertOrder(order);
        repository.insertOrderItem(orderId, order);
    }

    private void insertListing(int stock, BigDecimal price, String title, long version) {
        jdbcTemplate.update("""
                        INSERT INTO book_listing (
                            id, seller_id, title, author, description, unit_price,
                            currency, status, version, created_at, updated_at
                        ) VALUES (?, ?, ?, '测试作者', '测试描述', ?, 'CNY', 'ON_SALE', ?, ?, ?)
                        """,
                LISTING_ID,
                SELLER_ID,
                title,
                price,
                version,
                NOW,
                NOW
        );
        jdbcTemplate.update(
                "INSERT INTO sku_stock (listing_id, available, version, updated_at) VALUES (?, ?, 0, ?)",
                LISTING_ID,
                stock,
                NOW
        );
    }

    private NewOrder newOrder(String orderNo, String idempotencyKey, String fingerprint) {
        BigDecimal price = new BigDecimal("39.9000");
        return new NewOrder(
                orderNo,
                BUYER_ID,
                SELLER_ID,
                idempotencyKey,
                fingerprint,
                price,
                "CNY",
                NOW.plusSeconds(900),
                NOW,
                new OrderItemSnapshot(
                        LISTING_ID,
                        SELLER_ID,
                        3L,
                        "初始标题",
                        "测试作者",
                        price,
                        price,
                        1,
                        "CNY"
                )
        );
    }

    private int stock() {
        return jdbcTemplate.queryForObject(
                "SELECT available FROM sku_stock WHERE listing_id = ?",
                Integer.class,
                LISTING_ID
        );
    }
}
