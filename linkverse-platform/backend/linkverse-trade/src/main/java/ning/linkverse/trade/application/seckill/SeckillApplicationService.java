package ning.linkverse.trade.application.seckill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.core.error.CommonErrorCode;
import ning.linkverse.core.error.PlatformException;
import ning.linkverse.core.request.RequestContextKeys;
import ning.linkverse.messaging.MessageEnvelope;
import ning.linkverse.trade.domain.TradeErrorCode;
import ning.linkverse.trade.domain.seckill.SeckillCampaign;
import ning.linkverse.trade.domain.seckill.SeckillRepository;
import ning.linkverse.trade.domain.seckill.SeckillRequest;
import ning.linkverse.trade.domain.seckill.SeckillReservation;
import ning.linkverse.trade.infrastructure.seckill.SeckillRedisStore;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * SeckillApplicationService 编排依赖探活、Redis 准入和 MySQL 可恢复预约。
 *
 * @author ning
 * @date 2026-08-24
 */
@Service
public class SeckillApplicationService {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[\\x21-\\x7E]{8,64}");

    private final SeckillRepository repository;
    private final SeckillRedisStore redisStore;
    private final SeckillPersistenceService persistenceService;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SeckillApplicationService(
            SeckillRepository repository,
            SeckillRedisStore redisStore,
            SeckillPersistenceService persistenceService,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.redisStore = redisStore;
        this.persistenceService = persistenceService;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public SeckillCampaign campaign(long campaignId) {
        return repository.findCampaign(campaignId)
                .orElseThrow(() -> new PlatformException(TradeErrorCode.SECKILL_CAMPAIGN_NOT_FOUND));
    }

    public SeckillReservation reserve(long campaignId, long userId, String idempotencyKey) {
        validateKey(idempotencyKey);
        SeckillCampaign campaign = campaign(campaignId);
        Instant now = clock.instant();
        if (!campaign.accepts(now)) {
            throw new PlatformException(TradeErrorCode.SECKILL_CAMPAIGN_INACTIVE);
        }
        SeckillReservation existing = repository.findByCampaignAndUser(campaignId, userId).orElse(null);
        if (existing != null) {
            return existing;
        }
        assertRabbitAvailable();
        SeckillRequest request = new SeckillRequest(
                number(), number(), campaignId, campaign.version(), userId, idempotencyKey,
                fingerprint(campaignId, userId), now);
        SeckillRedisStore.Admission admission;
        try {
            int initializationStock = redisStore.initialized(campaign)
                    ? 0
                    : repository.authoritativeAvailable(campaign.listingId());
            admission = redisStore.reserve(campaign, request, initializationStock);
        } catch (RuntimeException exception) {
            throw new PlatformException(TradeErrorCode.SECKILL_DEPENDENCY_UNAVAILABLE);
        }
        if ("SOLD_OUT".equals(admission.status())) {
            throw new PlatformException(TradeErrorCode.SECKILL_SOLD_OUT);
        }
        if ("INACTIVE".equals(admission.status()) || "VERSION_MISMATCH".equals(admission.status())) {
            throw new PlatformException(TradeErrorCode.SECKILL_CAMPAIGN_INACTIVE);
        }
        if ("DUPLICATE".equals(admission.status())) {
            recoverPending(campaignId, admission.reservationNo());
            return repository.findByCampaignAndUser(campaignId, userId)
                    .orElseThrow(() -> new PlatformException(TradeErrorCode.SECKILL_REQUEST_PROCESSING));
        }
        String eventJson = eventJson(request);
        persistenceService.persistPending(request, eventJson);
        return repository.findReservation(request.reservationNo())
                .orElseThrow(() -> new IllegalStateException("保存秒杀预约后无法读取预约"));
    }

    public SeckillReservation ownedReservation(String reservationNo, long userId) {
        return repository.findReservationForUser(reservationNo, userId)
                .orElseThrow(() -> new PlatformException(TradeErrorCode.SECKILL_RESERVATION_NOT_FOUND));
    }

    public void recoverPending(long campaignId, String reservationNo) {
        for (SeckillRequest request : redisStore.pending(campaignId, clock.instant(), 200)) {
            if (reservationNo == null || reservationNo.equals(request.reservationNo())) {
                persistenceService.persistPending(request, eventJson(request));
            }
        }
    }

    private void assertRabbitAvailable() {
        try {
            Boolean available = rabbitTemplate.execute(channel -> channel.isOpen());
            if (!Boolean.TRUE.equals(available)) {
                throw new IllegalStateException("RabbitMQ 连接不可用");
            }
        } catch (RuntimeException exception) {
            throw new PlatformException(TradeErrorCode.SECKILL_DEPENDENCY_UNAVAILABLE);
        }
    }

    private String eventJson(SeckillRequest request) {
        try {
            return objectMapper.writeValueAsString(new MessageEnvelope<>(
                    request.eventId(), "SeckillRequested", 1, request.reservationNo(),
                    request.occurredAt(), traceId(), request));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("秒杀请求事件无法序列化", exception);
        }
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new PlatformException(TradeErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (!IDEMPOTENCY_KEY_PATTERN.matcher(key).matches()) {
            throw new PlatformException(TradeErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
    }

    private String fingerprint(long campaignId, long userId) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(("v1|" + campaignId + "|" + userId + "|1").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new PlatformException(CommonErrorCode.INTERNAL_ERROR);
        }
    }

    private String number() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String traceId() {
        String value = MDC.get(RequestContextKeys.TRACE_ID_MDC_KEY);
        return value == null || value.isBlank() ? "no-trace" : value;
    }
}
