package ning.linkverse.trade.domain.seckill;

import ning.linkverse.messaging.outbox.OutboxStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SeckillRepository 定义秒杀活动、预约、消费幂等和 Trade Outbox 契约。
 *
 * @author ning
 * @date 2026-08-24
 */
public interface SeckillRepository extends OutboxStore {

    Optional<SeckillCampaign> findCampaign(long campaignId);

    List<SeckillCampaign> findCampaigns();

    Optional<SeckillReservation> findReservation(String reservationNo);

    Optional<SeckillReservation> findReservationForUser(String reservationNo, long userId);

    Optional<SeckillReservation> findByCampaignAndUser(long campaignId, long userId);

    void insertPending(SeckillRequest request, String eventJson);

    boolean markPublished(String eventId, Instant now);

    Optional<SeckillRequest> lockRequest(String eventId);

    void markOrderCreated(long reservationId, String orderNo, Instant now, String resultEventId, String resultJson);

    void markFailed(long reservationId, String failureCode, Instant now, String resultEventId, String resultJson);

    boolean recordConsumed(String consumer, String eventId, String eventType, Instant now);

    List<SeckillReservation> findPendingBefore(Instant cutoff, int limit);

    int authoritativeAvailable(long listingId);

}
