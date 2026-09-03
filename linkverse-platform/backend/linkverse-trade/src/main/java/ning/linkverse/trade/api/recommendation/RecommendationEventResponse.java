package ning.linkverse.trade.api.recommendation;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RecommendationEventResponse 返回服务端生成的不可伪造事件标识。
 *
 * @author ning
 * @date 2026-09-03
 */
public record RecommendationEventResponse(@JsonProperty("event_id") String eventId) {
}
