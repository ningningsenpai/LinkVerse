package ning.linkverse.payment.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import ning.linkverse.payment.domain.CreatePaymentIntent;
import ning.linkverse.payment.domain.PaymentIntent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * PaymentIntentEntity 是 payment_intent 表的 MyBatis-Plus 持久化模型。
 *
 * @author ning
 * @date 2026-08-31
 */
@TableName("payment_intent")
public class PaymentIntentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String intentNo;
    private String orderNo;
    private long buyerId;
    private long merchantId;
    private BigDecimal amount;
    private String currency;
    private String provider;
    private String status;
    private String providerTxnNo;
    private Instant expireAt;
    private Instant succeededAt;
    private Instant closedAt;
    private Instant refundedAt;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public PaymentIntentEntity() {
    }

    public static PaymentIntentEntity create(
            CreatePaymentIntent command,
            String intentNo,
            String status,
            Instant now
    ) {
        PaymentIntentEntity entity = new PaymentIntentEntity();
        entity.intentNo = intentNo;
        entity.orderNo = command.orderNo();
        entity.buyerId = command.buyerId();
        entity.merchantId = command.merchantId();
        entity.amount = command.amount();
        entity.currency = command.currency();
        entity.provider = "MOCK";
        entity.status = status;
        entity.expireAt = command.expireAt();
        entity.closedAt = "CLOSED".equals(status) ? now : null;
        entity.version = 0;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public PaymentIntent toDomain() {
        return new PaymentIntent(
                id,
                intentNo,
                orderNo,
                buyerId,
                merchantId,
                amount,
                currency,
                provider,
                status,
                providerTxnNo,
                expireAt,
                succeededAt,
                closedAt,
                refundedAt,
                createdAt
        );
    }
}
