package ning.linkverse.trade.application.seckill;

import ning.linkverse.trade.domain.seckill.SeckillRepository;
import ning.linkverse.trade.domain.seckill.SeckillReservation;
import ning.linkverse.trade.infrastructure.seckill.SeckillRedisStore;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * SeckillMessageService 处理秒杀结果投影，并确保 Redis 成功后才记录消费完成。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class SeckillMessageService {

    private static final String CONSUMER = "trade-seckill-result";

    private final SeckillRepository repository;
    private final SeckillRedisStore redisStore;
    private final Clock clock;

    public SeckillMessageService(SeckillRepository repository, SeckillRedisStore redisStore, Clock clock) {
        this.repository = repository;
        this.redisStore = redisStore;
        this.clock = clock;
    }

    public void project(
            String eventId,
            String eventType,
            String reservationNo,
            long campaignId,
            long campaignVersion,
            String rawJson
    ) {
        SeckillReservation reservation = repository.findReservation(reservationNo)
                .orElseThrow(() -> new IllegalStateException("秒杀结果对应预约不存在"));
        if (reservation.campaignId() != campaignId || reservation.campaignVersion() != campaignVersion) {
            throw new IllegalArgumentException("秒杀结果与预约活动版本不一致");
        }
        if ("SeckillReservationRejected".equals(eventType)) {
            if (!"FAILED".equals(reservation.status())
                    && !"EXPIRED".equals(reservation.status())
                    && !"RELEASED".equals(reservation.status())) {
                throw new IllegalStateException("非失败预约不得执行 Redis 库存补偿");
            }
            redisStore.compensate(campaignId, campaignVersion, reservationNo, rawJson);
        } else {
            if (!"ORDER_CREATED".equals(reservation.status()) && !"COMMITTED".equals(reservation.status())) {
                throw new IllegalStateException("未建单预约不得投影成功结果");
            }
            redisStore.complete(campaignId, reservationNo, rawJson);
        }
        repository.recordConsumed(CONSUMER, eventId, eventType, clock.instant());
    }
}
