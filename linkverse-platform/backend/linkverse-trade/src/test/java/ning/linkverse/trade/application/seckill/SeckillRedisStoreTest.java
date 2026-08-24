package ning.linkverse.trade.application.seckill;

import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.trade.domain.seckill.SeckillCampaign;
import ning.linkverse.trade.domain.seckill.SeckillRequest;
import ning.linkverse.trade.infrastructure.seckill.SeckillRedisStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SeckillRedisStoreTest 在真实 Redis 上验证 Lua 无超卖和一人一次约束。
 *
 * @author ning
 * @date 2026-08-24
 */
@Testcontainers(disabledWithoutDocker = false)
class SeckillRedisStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-24T08:00:00Z");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.2.8"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static SeckillRedisStore store;

    @BeforeAll
    static void setUpRedis() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        store = new SeckillRedisStore(template, new ObjectMapper().findAndRegisterModules(), "test:lv:");
    }

    @AfterAll
    static void closeRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldAcceptOnlyOneHundredOfOneThousandUsers() throws Exception {
        SeckillCampaign campaign = campaign(30001, 100);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CountDownLatch ready = new CountDownLatch(1000);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<SeckillRedisStore.Admission>> futures = new ArrayList<>();
            for (int index = 0; index < 1000; index++) {
                int user = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return store.reserve(campaign, request(campaign.id(), user, user), 100);
                }));
            }
            ready.await();
            start.countDown();
            int accepted = 0;
            int soldOut = 0;
            for (Future<SeckillRedisStore.Admission> future : futures) {
                String status = future.get().status();
                accepted += "ACCEPTED".equals(status) ? 1 : 0;
                soldOut += "SOLD_OUT".equals(status) ? 1 : 0;
            }
            assertThat(accepted).isEqualTo(100);
            assertThat(soldOut).isEqualTo(900);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldKeepOnlyOneReservationForOneHundredConcurrentAttempts() throws Exception {
        SeckillCampaign campaign = campaign(30002, 100);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CountDownLatch ready = new CountDownLatch(100);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<SeckillRedisStore.Admission>> futures = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                int attempt = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return store.reserve(campaign, request(campaign.id(), 42, attempt), 100);
                }));
            }
            ready.await();
            start.countDown();
            List<SeckillRedisStore.Admission> admissions = new ArrayList<>();
            for (Future<SeckillRedisStore.Admission> future : futures) {
                admissions.add(future.get());
            }
            assertThat(admissions).filteredOn(item -> "ACCEPTED".equals(item.status())).hasSize(1);
            assertThat(admissions).extracting(SeckillRedisStore.Admission::reservationNo).doesNotContainNull();
            assertThat(admissions).extracting(SeckillRedisStore.Admission::reservationNo).containsOnly(
                    admissions.getFirst().reservationNo());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldNotRemoveAttemptMarkerAfterCompensation() {
        SeckillCampaign campaign = campaign(30003, 1);
        SeckillRequest first = request(campaign.id(), 7, 1);
        assertThat(store.reserve(campaign, first, 1).status()).isEqualTo("ACCEPTED");
        assertThat(store.compensate(campaign.id(), campaign.version(), first.reservationNo(), "失败"))
                .isTrue();
        assertThat(store.reserve(campaign, request(campaign.id(), 7, 2), 1).status()).isEqualTo("DUPLICATE");
        assertThat(store.reserve(campaign, request(campaign.id(), 8, 3), 1).status()).isEqualTo("ACCEPTED");
    }

    @Test
    void shouldRetainStablePendingRequestForCrashRecovery() {
        SeckillCampaign campaign = campaign(30004, 1);
        SeckillRequest request = request(campaign.id(), 9, 1);

        assertThat(store.reserve(campaign, request, 1).status()).isEqualTo("ACCEPTED");

        assertThat(store.pending(campaign.id(), NOW.plusSeconds(1), 10))
                .containsExactly(request);
    }

    private static SeckillCampaign campaign(long id, int stock) {
        return new SeckillCampaign(
                id, String.format("%032d", id), 10002, 90001, "秒杀商品", "测试作者",
                new BigDecimal("88.0000"), "CNY", 1, "ENABLED", stock,
                NOW.minusSeconds(60), NOW.plusSeconds(3600));
    }

    private static SeckillRequest request(long campaignId, long userId, int sequence) {
        return new SeckillRequest(
                String.format("%032x", campaignId * 10000 + sequence),
                String.format("%032x", campaignId * 20000 + sequence),
                campaignId,
                1,
                userId,
                "redis-test-" + sequence,
                String.format("%064d", sequence),
                NOW
        );
    }
}
