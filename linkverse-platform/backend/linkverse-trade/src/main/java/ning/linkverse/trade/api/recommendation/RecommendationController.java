package ning.linkverse.trade.api.recommendation;

import jakarta.validation.Valid;
import ning.linkverse.trade.application.behavior.BehaviorApplicationService;
import ning.linkverse.trade.application.recommendation.RecommendationApplicationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RecommendationController 暴露商品推荐和客户端可上报行为的公网契约。
 *
 * @author ning
 * @date 2026-09-03
 */
@RestController
@RequestMapping("/api/v1")
public class RecommendationController {

    private final RecommendationApplicationService recommendationService;
    private final BehaviorApplicationService behaviorService;

    public RecommendationController(
            RecommendationApplicationService recommendationService,
            BehaviorApplicationService behaviorService
    ) {
        this.recommendationService = recommendationService;
        this.behaviorService = behaviorService;
    }

    @GetMapping("/recommendations/listings")
    public RecommendationResponse listings(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "HOME") String scene,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(name = "context_listing_id", required = false) Long contextListingId
    ) {
        return RecommendationResponse.from(recommendationService.recommend(userId(jwt), scene, limit, contextListingId));
    }

    @PostMapping("/recommendation-events")
    public RecommendationEventResponse record(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RecommendationEventRequest request
    ) {
        return new RecommendationEventResponse(behaviorService.recordClientEvent(
                userId(jwt), request.listingId(), request.recommendationDeliveryId(),
                request.action(), request.sessionId()
        ));
    }

    private long userId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("用户令牌中的主体不是有效用户编号", exception);
        }
    }
}
