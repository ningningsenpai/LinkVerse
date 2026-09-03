package ning.linkverse.trade.application.recommendation;

import ning.linkverse.trade.domain.listing.BookListing;
import ning.linkverse.trade.domain.recommendation.RecommendationCandidate;
import ning.linkverse.trade.domain.recommendation.RecommendationDelivery;
import ning.linkverse.trade.domain.recommendation.RecommendationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RecommendationDeliveryService 在一个 Trade 本地事务中固化客户端可见的推荐列表。
 *
 * @author ning
 * @date 2026-09-03
 */
@Service
public class RecommendationDeliveryService {

    private final RecommendationRepository repository;

    public RecommendationDeliveryService(RecommendationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<DeliveredRecommendation> persist(
            long userId,
            String requestId,
            String modelVersion,
            String scene,
            List<BookListing> listings,
            Map<Long, RecommendationCandidate> candidates,
            Instant servedAt
    ) {
        List<DeliveredRecommendation> delivered = new ArrayList<>();
        for (int index = 0; index < listings.size(); index++) {
            BookListing listing = listings.get(index);
            RecommendationCandidate candidate = candidates.getOrDefault(
                    listing.id(),
                    new RecommendationCandidate(Long.toString(listing.id()), java.math.BigDecimal.ZERO,
                            List.of("FALLBACK"), "POPULAR_OR_NEW")
            );
            RecommendationDelivery saved = repository.insertDelivery(new RecommendationDelivery(
                    null,
                    requestId,
                    userId,
                    listing.id(),
                    index + 1,
                    modelVersion,
                    candidate.score(),
                    candidate.sources(),
                    candidate.reasonCode(),
                    scene,
                    "REAL",
                    servedAt
            ));
            delivered.add(new DeliveredRecommendation(
                    saved.id(), listing, saved.position(), saved.score(), saved.sources(),
                    saved.reasonCode(), reason(saved.reasonCode())
            ));
        }
        return List.copyOf(delivered);
    }

    private String reason(String reasonCode) {
        return switch (reasonCode) {
            case "CATEGORY_AFFINITY" -> "根据你感兴趣的分类推荐";
            case "AUTHOR_AFFINITY" -> "根据你喜欢的作者推荐";
            case "SIMILAR_ITEM" -> "与你浏览过的商品相似";
            case "NEW_EXPLORATION" -> "为你探索的新品";
            default -> "近期热门或新上架商品";
        };
    }
}
