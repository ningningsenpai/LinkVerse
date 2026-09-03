package ning.linkverse.trade.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import ning.linkverse.trade.domain.order.NewOrder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * TradeOrderEntity 是 trade_order 表的 MyBatis-Plus 持久化模型。
 *
 * @author ning
 * @date 2026-08-31
 */
@TableName("trade_order")
public class TradeOrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private long buyerId;
    private long sellerId;
    private String idempotencyKey;
    private String requestFingerprint;
    private Long recommendationDeliveryId;
    private String status;
    private BigDecimal totalAmount;
    private String currency;
    private Instant expireAt;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public TradeOrderEntity() {
    }

    public static TradeOrderEntity pending(NewOrder order) {
        TradeOrderEntity entity = new TradeOrderEntity();
        entity.orderNo = order.orderNo();
        entity.buyerId = order.buyerId();
        entity.sellerId = order.sellerId();
        entity.idempotencyKey = order.idempotencyKey();
        entity.requestFingerprint = order.requestFingerprint();
        entity.recommendationDeliveryId = order.recommendationDeliveryId();
        entity.status = "PENDING_PAYMENT";
        entity.totalAmount = order.totalAmount();
        entity.currency = order.currency();
        entity.expireAt = order.expireAt();
        entity.version = 0;
        entity.createdAt = order.now();
        entity.updatedAt = order.now();
        return entity;
    }

    public Long id() {
        return id;
    }
}
