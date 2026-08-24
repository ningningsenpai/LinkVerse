package ning.linkverse.trade.application.closing;

import ning.linkverse.trade.domain.TradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * OrderSettlementService 将订单终态与库存补偿限定在 Trade 本地事务。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class OrderSettlementService {

    private final TradeRepository repository;

    public OrderSettlementService(TradeRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean paid(String orderNo, String intentNo, Instant paidAt) {
        return repository.markPaid(orderNo, intentNo, paidAt);
    }

    @Transactional
    public boolean closed(String orderNo, Instant closedAt) {
        return repository.closeAndRestoreStock(orderNo, closedAt);
    }
}
