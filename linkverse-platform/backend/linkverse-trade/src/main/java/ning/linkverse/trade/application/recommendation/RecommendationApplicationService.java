package ning.linkverse.trade.application.recommendation;

import ning.linkverse.trade.domain.listing.BookListing;
import ning.linkverse.trade.domain.recommendation.RecommendationCandidate;
import ning.linkverse.trade.domain.recommendation.RecommendationRepository;
import ning.linkverse.trade.application.TradeBusinessMetrics;
import ning.linkverse.trade.application.TradeBusinessMetrics.RecommendationStage;
import ning.linkverse.trade.application.TradeBusinessMetrics.RecommendationFallback;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

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
    private final TradeBusinessMetrics metrics;

    public RecommendationApplicationService(
            RecommendationInternalClient client,
            RecommendationRepository repository,
            RecommendationDeliveryService deliveryService,
            UserKeyHasher userKeyHasher,
            Clock clock,
            TradeBusinessMetrics metrics
    ) {
        this.client = client;
        this.repository = repository;
        this.deliveryService = deliveryService;
        this.userKeyHasher = userKeyHasher;
        this.clock = clock;
        this.metrics = metrics;
    }

    public RecommendationResult recommend(long userId, String scene, int limit) {
        return recommend(userId, scene, limit, null);
    }

    public RecommendationResult recommend(long userId, String scene, int limit, Long contextListingId) {
        if (!SCENES.contains(scene) || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("推荐场景或数量不合法");
        }
        Instant now = clock.instant();
        if (contextListingId != null && contextListingId <= 0) {
            throw new IllegalArgumentException("上下文商品编号不合法");
        }
        long historyStarted = System.nanoTime();
        List<Long> recent;
        try {
            recent = repository.findRecentPositiveListingIds(userId, now);
        } finally {
            metrics.recommendationStage(RecommendationStage.HISTORY, System.nanoTime() - historyStarted);
        }
        Map<String, String> context = new LinkedHashMap<>();
        context.put("exclude_ids", recent.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        context.put("recent_positive_ids", context.get("exclude_ids"));
        if (contextListingId != null) {
            context.put("object_id", contextListingId.toString());
        }
        String requestId = compactUuid();
        String userKey = userKeyHasher.hash(userId);
        boolean fallback = false;
        String modelVersion = "trade-fallback-v1";
        Map<Long, RecommendationCandidate> candidates = new LinkedHashMap<>();
        long remoteStarted = System.nanoTime();
        boolean receivedResponse = false;
        try {
            RecommendationInternalResponse response = client.recommend(new RecommendationInternalRequest(
                    requestId,
                    userKey,
                    "trade",
                    scene,
                    now,
                    Math.min(500, Math.max(50, limit * 5)),
                    context
            ));
            receivedResponse = true;
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
            metrics.recommendationFallback(receivedResponse ? RecommendationFallback.INVALID_RESPONSE : failureReason(exception));
        } finally {
            metrics.recommendationStage(RecommendationStage.REMOTE, System.nanoTime() - remoteStarted);
        }

        long filterStarted = System.nanoTime();
        Map<Long, BookListing> eligibleById = new LinkedHashMap<>();
        for (BookListing listing : repository.findEligibleListings(userId, candidates.keySet(), now)) {
            eligibleById.put(listing.id(), listing);
        }
        List<BookListing> selected = new ArrayList<>();
        for (Long candidateId : candidates.keySet()) {
            BookListing listing = eligibleById.get(candidateId);
            if (listing != null && !recent.contains(listing.id()) && selected.size() < limit && withinQuota(selected, listing)) {
                selected.add(listing);
            }
        }
        if (selected.size() < limit) {
            if (!fallback) {
                metrics.recommendationFallback(RecommendationFallback.INSUFFICIENT_CANDIDATES);
            }
            List<Long> excluded = new ArrayList<>(recent);
            excluded.addAll(selected.stream().map(BookListing::id).toList());
            if (contextListingId != null) {
                excluded.add(contextListingId);
            }
            for (BookListing listing : repository.findFallbackListings(userId, excluded, now, 500)) {
                if (selected.size() < limit && withinQuota(selected, listing)) {
                    selected.add(listing);
                }
            }
            fallback = true;
        }
        metrics.recommendationStage(RecommendationStage.FILTER, System.nanoTime() - filterStarted);
        long deliveryStarted = System.nanoTime();
        List<DeliveredRecommendation> delivered;
        try {
            delivered = deliveryService.persist(userId, requestId, modelVersion, scene, selected, candidates, now);
        } finally {
            metrics.recommendationStage(RecommendationStage.DELIVERY, System.nanoTime() - deliveryStarted);
        }
        metrics.record("recommendation", fallback ? "fallback" : "model");
        return new RecommendationResult(requestId, modelVersion, fallback, delivered);
    }

    private RecommendationFallback failureReason(RuntimeException exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof java.net.http.HttpTimeoutException || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return RecommendationFallback.TIMEOUT;
            }
            if (current instanceof java.net.ConnectException) {
                return RecommendationFallback.CONNECTION;
            }
            if (current instanceof java.util.concurrent.RejectedExecutionException) {
                return RecommendationFallback.CONCURRENCY_LIMIT;
            }
            if (current instanceof RestClientResponseException) {
                return RecommendationFallback.HTTP_ERROR;
            }
            if (current.getClass().getSimpleName().equals("CallNotPermittedException")) {
                return RecommendationFallback.CIRCUIT_OPEN;
            }
        }
        return RecommendationFallback.REMOTE_ERROR;
    }

    private void validateResponse(String requestId, RecommendationInternalResponse response) {
        if (response == null || !requestId.equals(response.requestId()) || !"trade".equals(response.domain())
                || response.modelVersion() == null || response.modelVersion().isBlank()
                || response.candidates() == null) {
            throw new IllegalStateException("推荐服务响应与请求不匹配");
        }
    }

    private boolean withinQuota(List<BookListing> selected, BookListing candidate) {
        if (selected.stream().anyMatch(item -> item.id() == candidate.id())) {
            return false;
        }
        if (selected.size() >= 20) {
            return true;
        }
        return selected.stream().filter(item -> item.categoryId() == candidate.categoryId()).count() < 6
                && selected.stream().filter(item -> item.sellerId() == candidate.sellerId()).count() < 3;
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
