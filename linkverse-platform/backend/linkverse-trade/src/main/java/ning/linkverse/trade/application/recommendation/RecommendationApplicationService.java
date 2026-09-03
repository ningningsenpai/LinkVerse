package ning.linkverse.trade.application.recommendation;

import ning.linkverse.trade.domain.listing.BookListing;
import ning.linkverse.trade.domain.recommendation.RecommendationCandidate;
import ning.linkverse.trade.domain.recommendation.RecommendationRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RecommendationApplicationService 组织远程候选、Trade 权威过滤、数据库降级与补位。
 *
 * @author ning
 * @date 2026-09-03
 */
@Service
public class RecommendationApplicationService {

    private static final List<String> SCENES = List.of("HOME", "DETAIL", "CART");

    private final RecommendationInternalClient client;
    private final RecommendationRepository repository;
    private final RecommendationDeliveryService deliveryService;
    private final UserKeyHasher userKeyHasher;
    private final Clock clock;

    public RecommendationApplicationService(
            RecommendationInternalClient client,
            RecommendationRepository repository,
            RecommendationDeliveryService deliveryService,
            UserKeyHasher userKeyHasher,
            Clock clock
    ) {
        this.client = client;
        this.repository = repository;
        this.deliveryService = deliveryService;
        this.userKeyHasher = userKeyHasher;
        this.clock = clock;
    }

    public RecommendationResult recommend(long userId, String scene, int limit) {
        if (!SCENES.contains(scene) || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("推荐场景或数量不合法");
        }
        Instant now = clock.instant();
        String requestId = compactUuid();
        String userKey = userKeyHasher.hash(userId);
        boolean fallback = false;
        String modelVersion = "trade-fallback-v1";
        Map<Long, RecommendationCandidate> candidates = new LinkedHashMap<>();
        try {
            RecommendationInternalResponse response = client.recommend(new RecommendationInternalRequest(
                    requestId,
                    userKey,
                    "trade",
                    scene,
                    now,
                    Math.min(500, Math.max(50, limit * 5)),
                    Map.of()
            ));
            validateResponse(requestId, response);
            modelVersion = response.modelVersion();
            for (RecommendationCandidate candidate : response.candidates()) {
                validateCandidate(candidate);
                candidates.putIfAbsent(Long.parseLong(candidate.objectId()), candidate);
            }
        } catch (RuntimeException exception) {
            // 降级是业务可用性约束；远程错误不得阻断 Trade 读链路。
            fallback = true;
            candidates.clear();
        }

        Map<Long, BookListing> eligibleById = new LinkedHashMap<>();
        for (BookListing listing : repository.findEligibleListings(userId, candidates.keySet(), now)) {
            eligibleById.put(listing.id(), listing);
        }
        List<BookListing> selected = new ArrayList<>();
        for (Long candidateId : candidates.keySet()) {
            BookListing listing = eligibleById.get(candidateId);
            if (listing != null && selected.size() < limit) {
                selected.add(listing);
            }
        }
        if (selected.size() < limit) {
            List<Long> excluded = selected.stream().map(BookListing::id).toList();
            selected.addAll(repository.findFallbackListings(userId, excluded, now, limit - selected.size()));
            fallback = true;
        }
        List<DeliveredRecommendation> delivered = deliveryService.persist(
                userId, requestId, modelVersion, scene, selected, candidates, now
        );
        return new RecommendationResult(requestId, modelVersion, fallback, delivered);
    }

    private void validateResponse(String requestId, RecommendationInternalResponse response) {
        if (response == null || !requestId.equals(response.requestId()) || !"trade".equals(response.domain())
                || response.modelVersion() == null || response.modelVersion().isBlank()
                || response.candidates() == null) {
            throw new IllegalStateException("推荐服务响应与请求不匹配");
        }
    }

    private void validateCandidate(RecommendationCandidate candidate) {
        if (candidate == null || candidate.score() == null || candidate.sources() == null
                || candidate.sources().isEmpty() || candidate.reasonCode() == null
                || candidate.reasonCode().isBlank()) {
            throw new IllegalStateException("推荐候选字段不完整");
        }
        try {
            if (Long.parseLong(candidate.objectId()) <= 0) {
                throw new IllegalStateException("推荐候选商品编号不合法");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("推荐候选商品编号不合法", exception);
        }
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
