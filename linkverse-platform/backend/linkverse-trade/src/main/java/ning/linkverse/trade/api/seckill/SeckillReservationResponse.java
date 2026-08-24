package ning.linkverse.trade.api.seckill;

import com.fasterxml.jackson.annotation.JsonProperty;
import ning.linkverse.trade.domain.seckill.SeckillReservation;

import java.time.Instant;

/**
 * SeckillReservationResponse 返回预约处理状态与已生成订单号。
 *
 * @author ning
 * @date 2026-08-24
 */
public record SeckillReservationResponse(
        @JsonProperty("reservation_no") String reservationNo,
        @JsonProperty("campaign_id") long campaignId,
        String status,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("failure_code") String failureCode,
        @JsonProperty("updated_at") Instant updatedAt
) {

    static SeckillReservationResponse from(SeckillReservation reservation) {
        return new SeckillReservationResponse(
                reservation.reservationNo(), reservation.campaignId(), reservation.status(),
                reservation.orderNo(), reservation.failureCode(), reservation.updatedAt());
    }
}
