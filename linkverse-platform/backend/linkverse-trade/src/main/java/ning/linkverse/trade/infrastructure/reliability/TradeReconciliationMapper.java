package ning.linkverse.trade.infrastructure.reliability;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * TradeReconciliationMapper 查询交易域长期未收敛状态及跨表差异。
 *
 * @author ning
 * @date 2026-08-31
 */
@Mapper
public interface TradeReconciliationMapper {

    @Select("""
            SELECT COUNT(*) FROM trade_order
            WHERE status = 'CLOSING'
              AND updated_at < CURRENT_TIMESTAMP(6) - INTERVAL 1 MINUTE
            """)
    long countClosingOrders();

    @Select("""
            SELECT COUNT(*) FROM seckill_reservation
            WHERE status IN ('PUBLISH_PENDING', 'PUBLISHED')
              AND updated_at < CURRENT_TIMESTAMP(6) - INTERVAL 1 MINUTE
            """)
    long countIntermediateReservations();

    @Select("""
            SELECT COUNT(*)
            FROM seckill_reservation r
            LEFT JOIN trade_order o ON o.reservation_id = r.id
            WHERE (r.status = 'ORDER_CREATED' AND o.id IS NULL)
               OR (o.id IS NOT NULL AND r.order_no <> o.order_no)
            """)
    long countReservationOrderMismatches();
}
