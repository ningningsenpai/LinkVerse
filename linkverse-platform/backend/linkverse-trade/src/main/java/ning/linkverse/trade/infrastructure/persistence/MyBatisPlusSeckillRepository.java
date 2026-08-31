package ning.linkverse.trade.infrastructure.persistence;

import ning.linkverse.messaging.outbox.OutboxEventView;
import ning.linkverse.messaging.outbox.OutboxMessage;
import ning.linkverse.messaging.outbox.OutboxStats;
import ning.linkverse.trade.domain.seckill.SeckillCampaign;
import ning.linkverse.trade.domain.seckill.SeckillRepository;
import ning.linkverse.trade.domain.seckill.SeckillRequest;
import ning.linkverse.trade.domain.seckill.SeckillReservation;
import ning.linkverse.trade.infrastructure.persistence.entity.SeckillReservationEntity;
import ning.linkverse.trade.infrastructure.persistence.mapper.SeckillMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MyBatisPlusSeckillRepository 将秒杀权威状态、消费幂等和 Outbox 限定在 Trade Schema。
 *
 * @author ning
 * @date 2026-08-31
 */
@Repository
public class MyBatisPlusSeckillRepository implements SeckillRepository {

    private final SeckillMapper mapper;

    public MyBatisPlusSeckillRepository(SeckillMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<SeckillCampaign> findCampaign(long campaignId) {
        return Optional.ofNullable(mapper.selectCampaign(campaignId));
    }

    @Override
    public List<SeckillCampaign> findCampaigns() {
        return mapper.selectCampaigns();
    }

    @Override
    public Optional<SeckillReservation> findReservation(String reservationNo) {
        return Optional.ofNullable(mapper.selectReservation(reservationNo));
    }

    @Override
    public Optional<SeckillReservation> findReservationForUser(String reservationNo, long userId) {
        return Optional.ofNullable(mapper.selectReservationForUser(reservationNo, userId));
    }

    @Override
    public Optional<SeckillReservation> findByCampaignAndUser(long campaignId, long userId) {
        return Optional.ofNullable(mapper.selectByCampaignAndUser(campaignId, userId));
    }

    @Override
    public void insertPending(SeckillRequest request, String eventJson) {
        mapper.insert(SeckillReservationEntity.publishPending(request));
        insertOutbox(
                request.eventId(),
                request.reservationNo(),
                "SeckillRequested",
                "seckill.request",
                eventJson,
                request.occurredAt()
        );
    }

    @Override
    public boolean markPublished(String eventId, Instant now) {
        return mapper.markReservationPublished(eventId, now) == 1;
    }

    @Override
    public Optional<SeckillRequest> lockRequest(String eventId) {
        return Optional.ofNullable(mapper.lockRequest(eventId));
    }

    @Override
    public void markOrderCreated(
            long reservationId,
            String orderNo,
            Instant now,
            String resultEventId,
            String resultJson
    ) {
        mapper.markOrderCreated(reservationId, orderNo, now);
        insertOutbox(
                resultEventId,
                Long.toString(reservationId),
                "SeckillReservationSucceeded",
                "seckill.result",
                resultJson,
                now
        );
    }

    @Override
    public void markFailed(
            long reservationId,
            String failureCode,
            Instant now,
            String resultEventId,
            String resultJson
    ) {
        mapper.markFailed(reservationId, failureCode, now);
        insertOutbox(
                resultEventId,
                Long.toString(reservationId),
                "SeckillReservationRejected",
                "seckill.result",
                resultJson,
                now
        );
    }

    @Override
    public boolean recordConsumed(String consumer, String eventId, String eventType, Instant now) {
        try {
            return mapper.insertConsumed(consumer, eventId, eventType, now) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public List<SeckillReservation> findPendingBefore(Instant cutoff, int limit) {
        return mapper.selectPendingBefore(cutoff, limit);
    }

    @Override
    public int authoritativeAvailable(long listingId) {
        Integer available = mapper.selectAuthoritativeAvailable(listingId);
        return available == null ? 0 : available;
    }

    @Override
    @Transactional
    public List<OutboxMessage> claim(Instant now, Instant lockedUntil, int limit) {
        List<OutboxMessage> messages = mapper.claim(now, limit);
        for (OutboxMessage message : messages) {
            mapper.markSending(message.id(), lockedUntil);
        }
        return messages;
    }

    @Override
    @Transactional
    public void markPublished(long id, Instant publishedAt) {
        String eventId = mapper.selectOutboxEventId(id);
        mapper.markOutboxPublished(id, publishedAt);
        if (eventId != null) {
            mapper.markReservationPublished(eventId, publishedAt);
        }
    }

    @Override
    public void markFailed(long id, int attempts, Instant nextAttemptAt, String errorDigest, boolean parked) {
        mapper.markOutboxFailed(
                id,
                parked ? "PARKED" : "PENDING",
                attempts,
                nextAttemptAt,
                errorDigest
        );
    }

    @Override
    public Optional<OutboxEventView> findByEventId(String eventId) {
        return Optional.ofNullable(mapper.selectOutboxByEventId(eventId));
    }

    @Override
    public boolean replayParked(String eventId, Instant now) {
        return mapper.replayParked(eventId, now) == 1;
    }

    @Override
    public OutboxStats stats(Instant now) {
        return mapper.selectStats(now);
    }

    private void insertOutbox(
            String eventId,
            String aggregateId,
            String eventType,
            String routingKey,
            String eventJson,
            Instant now
    ) {
        mapper.insertOutbox(eventId, aggregateId, eventType, routingKey, eventJson, now);
    }
}
