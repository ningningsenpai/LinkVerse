package ning.linkverse.payment.infrastructure.reliability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * PaymentReconciliationService 只报告支付域未知或长期未收敛状态，不猜测外部渠道结果。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class PaymentReconciliationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong expiredPending = new AtomicLong();
    private final AtomicLong uncertainRefund = new AtomicLong();
    private final AtomicLong openException = new AtomicLong();

    public PaymentReconciliationService(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        register(meterRegistry, "expired_pending", expiredPending);
        register(meterRegistry, "uncertain_refund", uncertainRefund);
        register(meterRegistry, "open_exception", openException);
    }

    @Scheduled(fixedDelayString = "${linkverse.payment.reconciliation-fixed-delay:30000}")
    public Snapshot reconcile() {
        Snapshot snapshot = new Snapshot(
                count("""
                        SELECT COUNT(*) FROM payment_intent
                        WHERE status = 'PENDING' AND expire_at < CURRENT_TIMESTAMP(6)
                        """),
                count("""
                        SELECT COUNT(*) FROM refund_attempt
                        WHERE status IN ('PENDING', 'UNKNOWN')
                          AND updated_at < CURRENT_TIMESTAMP(6) - INTERVAL 1 MINUTE
                        """),
                count("""
                        SELECT COUNT(*) FROM payment_exception
                        WHERE status <> 'RESOLVED'
                        """)
        );
        expiredPending.set(snapshot.expiredPending());
        uncertainRefund.set(snapshot.uncertainRefund());
        openException.set(snapshot.openException());
        if (snapshot.total() > 0) {
            LOGGER.warn(
                    "支付对账发现差异 expired_pending={} uncertain_refund={} open_exception={}",
                    snapshot.expiredPending(), snapshot.uncertainRefund(), snapshot.openException());
        }
        return snapshot;
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private void register(MeterRegistry registry, String type, AtomicLong value) {
        Gauge.builder("linkverse.reconciliation.difference", value, AtomicLong::get)
                .tag("domain", "payment")
                .tag("type", type)
                .register(registry);
    }

    public record Snapshot(long expiredPending, long uncertainRefund, long openException) {
        public long total() {
            return expiredPending + uncertainRefund + openException;
        }
    }
}
