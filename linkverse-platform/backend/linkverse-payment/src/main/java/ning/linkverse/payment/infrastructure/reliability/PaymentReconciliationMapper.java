package ning.linkverse.payment.infrastructure.reliability;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * PaymentReconciliationMapper 查询支付域长期未收敛状态。
 *
 * @author ning
 * @date 2026-08-31
 */
@Mapper
public interface PaymentReconciliationMapper {

    @Select("""
            SELECT COUNT(*) FROM payment_intent
            WHERE status = 'PENDING' AND expire_at < CURRENT_TIMESTAMP(6)
            """)
    long countExpiredPending();

    @Select("""
            SELECT COUNT(*) FROM refund_attempt
            WHERE status IN ('PENDING', 'UNKNOWN')
              AND updated_at < CURRENT_TIMESTAMP(6) - INTERVAL 1 MINUTE
            """)
    long countUncertainRefunds();

    @Select("""
            SELECT COUNT(*) FROM payment_exception
            WHERE status <> 'RESOLVED'
            """)
    long countOpenExceptions();
}
