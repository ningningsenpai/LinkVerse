package ning.linkverse.trade.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ning.linkverse.trade.domain.behavior.OrderBehaviorTarget;
import ning.linkverse.trade.domain.behavior.TradeBehaviorEvent;
import ning.linkverse.trade.domain.cart.CartItem;
import ning.linkverse.trade.domain.listing.BookListing;
import ning.linkverse.trade.domain.recommendation.RecommendationDelivery;
import ning.linkverse.trade.domain.recommendation.RecommendationRepository;
import ning.linkverse.trade.infrastructure.persistence.mapper.RecommendationMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * MyBatisPlusRecommendationRepository 使用 Trade Schema 持久化推荐闭环事实。
 *
 * @author ning
 * @date 2026-09-03
 */
@Repository
public class MyBatisPlusRecommendationRepository implements RecommendationRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final RecommendationMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisPlusRecommendationRepository(RecommendationMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<BookListing> findEligibleListings(long userId, Collection<Long> listingIds, Instant now) {
        if (listingIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectEligibleListings(userId, listingIds, now);
    }

    @Override
    public List<BookListing> findFallbackListings(
            long userId,
            Collection<Long> excludedIds,
            Instant now,
            int limit
    ) {
        return mapper.selectFallbackListings(userId, excludedIds, now, limit);
    }

    @Override
    public RecommendationDelivery insertDelivery(RecommendationDelivery delivery) {
        RecommendationMapper.DeliveryRow row = toRow(delivery);
        mapper.insertDelivery(row);
        return fromRow(row);
    }

    @Override
    public Optional<RecommendationDelivery> findDelivery(long deliveryId, long userId, long listingId) {
        return Optional.ofNullable(mapper.selectDelivery(deliveryId, userId, listingId)).map(this::fromRow);
    }

    @Override
    public List<CartItem> findCartItems(long userId) {
        return mapper.selectCartItems(userId);
    }

    @Override
    public boolean addCartItem(long userId, long listingId, Long deliveryId, Instant now) {
        return mapper.insertCartItem(userId, listingId, deliveryId, now) == 1;
    }

    @Override
    public boolean deleteCartItem(long userId, long listingId) {
        return mapper.deleteCartItem(userId, listingId) == 1;
    }

    @Override
    public void insertBehavior(TradeBehaviorEvent event) {
        mapper.insertBehavior(event);
    }

    @Override
    public Optional<OrderBehaviorTarget> findOrderBehaviorTarget(String orderNo) {
        return Optional.ofNullable(mapper.selectOrderBehaviorTarget(orderNo));
    }

    private RecommendationMapper.DeliveryRow toRow(RecommendationDelivery delivery) {
        try {
            RecommendationMapper.DeliveryRow row = new RecommendationMapper.DeliveryRow();
            row.id = delivery.id();
            row.requestId = delivery.requestId();
            row.userId = delivery.userId();
            row.listingId = delivery.listingId();
            row.position = delivery.position();
            row.modelVersion = delivery.modelVersion();
            row.score = delivery.score();
            row.sourcesJson = objectMapper.writeValueAsString(delivery.sources());
            row.reasonCode = delivery.reasonCode();
            row.scene = delivery.scene();
            row.dataSource = delivery.dataSource();
            row.servedAt = delivery.servedAt();
            return row;
        } catch (Exception exception) {
            throw new IllegalStateException("序列化推荐来源失败", exception);
        }
    }

    private RecommendationDelivery fromRow(RecommendationMapper.DeliveryRow row) {
        try {
            return new RecommendationDelivery(
                    row.id,
                    row.requestId,
                    row.userId,
                    row.listingId,
                    row.position,
                    row.modelVersion,
                    row.score,
                    objectMapper.readValue(row.sourcesJson, STRING_LIST),
                    row.reasonCode,
                    row.scene,
                    row.dataSource,
                    row.servedAt
            );
        } catch (Exception exception) {
            throw new IllegalStateException("解析推荐来源失败", exception);
        }
    }
}
