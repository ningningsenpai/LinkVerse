package ning.linkverse.trade.application.behavior;

import ning.linkverse.core.error.PlatformException;
import ning.linkverse.trade.domain.TradeErrorCode;
import ning.linkverse.trade.domain.behavior.BehaviorAction;
import ning.linkverse.trade.domain.behavior.TradeBehaviorEvent;
import ning.linkverse.trade.domain.recommendation.RecommendationDelivery;
import ning.linkverse.trade.domain.recommendation.RecommendationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * BehaviorApplicationService 只允许客户端记录可观测行为，并从 delivery 恢复不可伪造的模型上下文。
 *
 * @author ning
 * @date 2026-09-03
 */
@Service
public class BehaviorApplicationService {

    private final RecommendationRepository repository;
    private final Clock clock;

    public BehaviorApplicationService(RecommendationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public String recordClientEvent(
            long userId,
            long listingId,
            long deliveryId,
            String actionValue,
            String sessionId
    ) {
        BehaviorAction action = parseClientAction(actionValue);
        RecommendationDelivery delivery = repository.findDelivery(deliveryId, userId, listingId)
                .orElseThrow(() -> new PlatformException(TradeErrorCode.RECOMMENDATION_DELIVERY_INVALID));
        Instant now = clock.instant();
        String eventId = compactUuid();
        repository.insertBehavior(new TradeBehaviorEvent(
                eventId,
                userId,
                listingId,
                action,
                now,
                now,
                delivery.id(),
                delivery.requestId(),
                normalizeSession(sessionId),
                delivery.position(),
                delivery.sources().getFirst(),
                delivery.modelVersion(),
                null,
                null,
                "REAL"
        ));
        return eventId;
    }

    private BehaviorAction parseClientAction(String value) {
        if ("IMPRESSION".equals(value)) {
            return BehaviorAction.IMPRESSION;
        }
        if ("DETAIL_OPEN".equals(value)) {
            return BehaviorAction.DETAIL_OPEN;
        }
        throw new PlatformException(TradeErrorCode.RECOMMENDATION_EVENT_INVALID);
    }

    private String normalizeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
            throw new PlatformException(TradeErrorCode.RECOMMENDATION_EVENT_INVALID);
        }
        return sessionId;
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
