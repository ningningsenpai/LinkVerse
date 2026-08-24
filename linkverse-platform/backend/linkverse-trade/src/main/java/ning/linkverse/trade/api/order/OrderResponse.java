package ning.linkverse.trade.api.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import ning.linkverse.trade.domain.order.TradeOrder;

import java.time.Instant;

/**
 * OrderResponse 定义订单及服务端金额快照的外部表示。
 *
 * @author ning
 * @date 2026-08-24
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
        @JsonProperty("order_no") String orderNo,
        String status,
        @JsonProperty("total_amount") String totalAmount,
        String currency,
        @JsonProperty("expire_at") Instant expireAt,
        @JsonProperty("created_at") Instant createdAt,
        OrderItemResponse item
) {

    static OrderResponse from(TradeOrder order, boolean includeItem) {
        return new OrderResponse(
                order.orderNo(),
                order.status(),
                order.totalAmount().setScale(4).toPlainString(),
                order.currency(),
                order.expireAt(),
                order.createdAt(),
                includeItem ? OrderItemResponse.from(order.item()) : null
        );
    }
}
