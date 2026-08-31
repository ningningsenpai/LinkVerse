package ning.linkverse.trade.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import ning.linkverse.trade.domain.seckill.SeckillRequest;

import java.time.Instant;

/**
 * SeckillReservationEntity 是 seckill_reservation 表的 MyBatis-Plus 持久化模型。
 *
 * @author ning
 * @date 2026-08-31
 */
@TableName("seckill_reservation")
public class SeckillReservationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String reservationNo;
    private String eventId;
    private long campaignId;
    private long campaignVersion;
    private long userId;
    private String idempotencyKey;
    private String requestFingerprint;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

    public SeckillReservationEntity() {
    }

    public static SeckillReservationEntity publishPending(SeckillRequest request) {
        SeckillReservationEntity entity = new SeckillReservationEntity();
        entity.reservationNo = request.reservationNo();
        entity.eventId = request.eventId();
        entity.campaignId = request.campaignId();
        entity.campaignVersion = request.campaignVersion();
        entity.userId = request.userId();
        entity.idempotencyKey = request.idempotencyKey();
        entity.requestFingerprint = request.requestFingerprint();
        entity.status = "PUBLISH_PENDING";
        entity.createdAt = request.occurredAt();
        entity.updatedAt = request.occurredAt();
        return entity;
    }
}
