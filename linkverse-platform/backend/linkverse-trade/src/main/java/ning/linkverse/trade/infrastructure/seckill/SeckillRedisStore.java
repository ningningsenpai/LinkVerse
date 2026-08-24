package ning.linkverse.trade.infrastructure.seckill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.trade.domain.seckill.SeckillCampaign;
import ning.linkverse.trade.domain.seckill.SeckillRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SeckillRedisStore 以同一 Hash Tag 内的 Lua 脚本完成准入、待发布记录和条件补偿。
 *
 * @author ning
 * @date 2026-08-24
 */
@Component
public class SeckillRedisStore {

    private static final DefaultRedisScript<String> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            local currentVersion = redis.call('HGET', KEYS[1], 'version')
            if not currentVersion then
              redis.call('HSET', KEYS[1], 'version', ARGV[1], 'status', ARGV[2],
                'starts_at', ARGV[3], 'ends_at', ARGV[4], 'stock', ARGV[5])
              currentVersion = ARGV[1]
            end
            if currentVersion ~= ARGV[1] then return 'VERSION_MISMATCH' end
            if redis.call('HGET', KEYS[1], 'status') ~= 'ENABLED' then return 'INACTIVE' end
            local now = tonumber(ARGV[6])
            if now < tonumber(redis.call('HGET', KEYS[1], 'starts_at')) or
               now >= tonumber(redis.call('HGET', KEYS[1], 'ends_at')) then return 'INACTIVE' end
            local existing = redis.call('HGET', KEYS[2], ARGV[7])
            if existing then return 'DUPLICATE|' .. existing end
            if tonumber(redis.call('HGET', KEYS[1], 'stock')) <= 0 then return 'SOLD_OUT' end
            redis.call('HINCRBY', KEYS[1], 'stock', -1)
            redis.call('HSET', KEYS[2], ARGV[7], ARGV[8])
            redis.call('HSET', KEYS[3], ARGV[8], ARGV[9])
            redis.call('ZADD', KEYS[4], ARGV[6], ARGV[8])
            return 'ACCEPTED|' .. ARGV[8]
            """, String.class);

    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT = new DefaultRedisScript<>("""
            if not redis.call('HGET', KEYS[2], ARGV[1]) then return 0 end
            if redis.call('HGET', KEYS[1], 'version') ~= ARGV[2] then return 0 end
            redis.call('HDEL', KEYS[2], ARGV[1])
            redis.call('ZREM', KEYS[3], ARGV[1])
            redis.call('HINCRBY', KEYS[1], 'stock', 1)
            redis.call('HSET', KEYS[4], ARGV[1], ARGV[3])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> ALIGN_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'version') ~= ARGV[1] then return 0 end
            if redis.call('ZCARD', KEYS[2]) ~= 0 then return 0 end
            redis.call('HSET', KEYS[1], 'stock', ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String prefix;

    public SeckillRedisStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${linkverse.trade.seckill.redis-prefix:lv:mvp:trade:}") String prefix
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.prefix = prefix;
    }

    public Admission reserve(SeckillCampaign campaign, SeckillRequest request, int authoritativeStock) {
        String payload = json(request);
        List<String> keys = keys(campaign.id());
        String value = redisTemplate.execute(
                RESERVE_SCRIPT,
                keys.subList(0, 4),
                Long.toString(campaign.version()),
                campaign.status(),
                Long.toString(campaign.startsAt().toEpochMilli()),
                Long.toString(campaign.endsAt().toEpochMilli()),
                Integer.toString(authoritativeStock),
                Long.toString(request.occurredAt().toEpochMilli()),
                Long.toString(request.userId()),
                request.reservationNo(),
                payload
        );
        if (value == null) {
            throw new IllegalStateException("Redis 秒杀准入未返回结果");
        }
        String[] parts = value.split("\\|", 2);
        return new Admission(parts[0], parts.length == 2 ? parts[1] : null);
    }

    public boolean initialized(SeckillCampaign campaign) {
        Object version = redisTemplate.opsForHash().get(keys(campaign.id()).getFirst(), "version");
        return Long.toString(campaign.version()).equals(version);
    }

    public int stock(long campaignId) {
        Object value = redisTemplate.opsForHash().get(keys(campaignId).getFirst(), "stock");
        return value == null ? -1 : Integer.parseInt(value.toString());
    }

    public long pendingCount(long campaignId) {
        Long value = redisTemplate.opsForZSet().zCard(keys(campaignId).get(3));
        return value == null ? 0 : value;
    }

    public List<SeckillRequest> pending(long campaignId, Instant cutoff, int limit) {
        List<String> keys = keys(campaignId);
        var numbers = redisTemplate.opsForZSet().rangeByScore(keys.get(3), 0, cutoff.toEpochMilli(), 0, limit);
        List<SeckillRequest> requests = new ArrayList<>();
        if (numbers == null) {
            return requests;
        }
        for (String number : numbers) {
            Object raw = redisTemplate.opsForHash().get(keys.get(2), number);
            if (raw instanceof String json) {
                try {
                    requests.add(objectMapper.readValue(json, SeckillRequest.class));
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException("Redis 秒杀待发布记录无法解析", exception);
                }
            }
        }
        return requests;
    }

    public void complete(long campaignId, String reservationNo, String resultJson) {
        List<String> keys = keys(campaignId);
        redisTemplate.opsForHash().delete(keys.get(2), reservationNo);
        redisTemplate.opsForZSet().remove(keys.get(3), reservationNo);
        redisTemplate.opsForHash().put(keys.get(4), reservationNo, resultJson);
    }

    public boolean compensate(long campaignId, long version, String reservationNo, String resultJson) {
        List<String> keys = keys(campaignId);
        Long restored = redisTemplate.execute(
                COMPENSATE_SCRIPT,
                List.of(keys.get(0), keys.get(2), keys.get(3), keys.get(4)),
                reservationNo,
                Long.toString(version),
                resultJson
        );
        return Long.valueOf(1).equals(restored);
    }

    public boolean alignStockIfIdle(SeckillCampaign campaign, int available) {
        List<String> keys = keys(campaign.id());
        Long aligned = redisTemplate.execute(
                ALIGN_SCRIPT,
                List.of(keys.get(0), keys.get(3)),
                Long.toString(campaign.version()),
                Integer.toString(available)
        );
        return Long.valueOf(1).equals(aligned);
    }

    private List<String> keys(long campaignId) {
        String base = prefix + "{seckill:" + campaignId + "}:";
        return List.of(base + "campaign", base + "users", base + "pending", base + "pending-order", base + "results");
    }

    private String json(SeckillRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("秒杀待发布记录无法序列化", exception);
        }
    }

    public record Admission(String status, String reservationNo) {
    }
}
