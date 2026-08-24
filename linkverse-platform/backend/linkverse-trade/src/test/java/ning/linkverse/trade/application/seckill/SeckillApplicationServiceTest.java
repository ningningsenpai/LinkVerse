package ning.linkverse.trade.application.seckill;

import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.core.error.PlatformException;
import ning.linkverse.trade.domain.TradeErrorCode;
import ning.linkverse.trade.domain.seckill.SeckillCampaign;
import ning.linkverse.trade.domain.seckill.SeckillRepository;
import ning.linkverse.trade.infrastructure.seckill.SeckillRedisStore;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SeckillApplicationServiceTest 验证削峰依赖不可用时在 Redis 预扣前关闭新准入。
 *
 * @author ning
 * @date 2026-08-24
 */
class SeckillApplicationServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectBeforeRedisReservationWhenRabbitMqIsUnavailable() {
        Instant now = Instant.parse("2026-08-24T08:00:00Z");
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillRedisStore redisStore = mock(SeckillRedisStore.class);
        SeckillPersistenceService persistenceService = mock(SeckillPersistenceService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(repository.findCampaign(20001)).thenReturn(Optional.of(new SeckillCampaign(
                20001, "1".repeat(32), 10002, 90001, "秒杀商品", "测试作者",
                new BigDecimal("88.0000"), "CNY", 1, "ENABLED", 100,
                now.minusSeconds(60), now.plusSeconds(3600))));
        when(repository.findByCampaignAndUser(20001, 10001)).thenReturn(Optional.empty());
        when(rabbitTemplate.execute(any(ChannelCallback.class)))
                .thenThrow(new IllegalStateException("模拟 RabbitMQ 中断"));
        SeckillApplicationService service = new SeckillApplicationService(
                repository,
                redisStore,
                persistenceService,
                rabbitTemplate,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(now, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.reserve(20001, 10001, "rabbit-down-key"))
                .isInstanceOfSatisfying(PlatformException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(TradeErrorCode.SECKILL_DEPENDENCY_UNAVAILABLE));
        org.mockito.Mockito.verifyNoInteractions(redisStore, persistenceService);
    }
}
