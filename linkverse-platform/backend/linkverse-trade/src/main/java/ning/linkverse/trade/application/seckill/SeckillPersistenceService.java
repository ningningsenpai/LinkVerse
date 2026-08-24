package ning.linkverse.trade.application.seckill;

import ning.linkverse.trade.domain.seckill.SeckillRepository;
import ning.linkverse.trade.domain.seckill.SeckillRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SeckillPersistenceService 保证预约墓碑与请求 Outbox 在同一 MySQL 事务提交。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class SeckillPersistenceService {

    private final SeckillRepository repository;

    public SeckillPersistenceService(SeckillRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void persistPending(SeckillRequest request, String eventJson) {
        try {
            repository.insertPending(request, eventJson);
        } catch (DuplicateKeyException exception) {
            if (repository.findByCampaignAndUser(request.campaignId(), request.userId()).isEmpty()) {
                throw exception;
            }
        }
    }
}
