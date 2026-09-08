package ning.linkverse.trade.infrastructure.persistence.mapper;

import ning.linkverse.trade.domain.behavior.OrderBehaviorTarget;
import ning.linkverse.trade.domain.behavior.TradeBehaviorEvent;
import ning.linkverse.trade.domain.cart.CartItem;
import ning.linkverse.trade.domain.listing.BookListing;
import ning.linkverse.trade.domain.recommendation.RecommendationDelivery;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * RecommendationMapper 提供 Trade 推荐闭环所需的单库读写。
 *
 * @author ning
 * @date 2026-09-03
 */
@Mapper
public interface RecommendationMapper {

    @Select("""
            SELECT listing_id FROM (
                SELECT listing_id, action, ROW_NUMBER() OVER (
                    PARTITION BY listing_id ORDER BY ingested_at DESC, id DESC
                ) AS sequence_no, ingested_at
                FROM trade_behavior_event
                WHERE user_id = #{userId} AND ingested_at <= #{now}
                  AND ingested_at >= DATE_SUB(#{now}, INTERVAL 1 DAY)
                  AND (action IN ('DETAIL_OPEN', 'CART_ADD', 'ORDER_CREATED', 'PAYMENT_SUCCEEDED')
                    OR (action = 'REFUNDED' AND refund_reason_code IN ('USER_RETURN', 'QUALITY_ISSUE')))
            ) recent WHERE sequence_no = 1 AND action <> 'REFUNDED'
            ORDER BY ingested_at DESC, listing_id LIMIT 50
            """)
    List<Long> selectRecentPositiveListingIds(@Param("userId") long userId, @Param("now") Instant now);

    String ELIGIBLE_SELECT = """
            SELECT l.id AS id, l.seller_id AS sellerId, l.category_id AS categoryId,
                   c.code AS categoryCode, l.title AS title, l.author AS author,
                   l.description AS description, l.unit_price AS unitPrice,
                   l.currency AS currency, l.status AS status, l.published_at AS publishedAt,
                   l.version AS version, s.available AS available, l.updated_at AS updatedAt
            FROM book_listing l
            JOIN book_category c ON c.id = l.category_id AND c.status = 'ACTIVE'
            JOIN sku_stock s ON s.listing_id = l.id AND s.available > 0
            WHERE l.status = 'ON_SALE' AND l.seller_id &lt;&gt; #{userId}
              AND NOT EXISTS (
                SELECT 1 FROM cart_item ci WHERE ci.user_id = #{userId} AND ci.listing_id = l.id
              )
              AND NOT EXISTS (
                SELECT 1 FROM trade_order o
                JOIN order_item oi ON oi.order_id = o.id
                WHERE o.buyer_id = #{userId} AND o.status = 'PAID' AND oi.listing_id = l.id
              )
              AND NOT EXISTS (
                SELECT 1 FROM seckill_campaign sc
                WHERE sc.listing_id = l.id AND sc.status = 'ENABLED'
                  AND sc.starts_at &lt;= #{now} AND sc.ends_at &gt; #{now}
              )
            """;

    @Select("""
            <script>
            """ + ELIGIBLE_SELECT + """
              AND l.id IN
              <foreach collection="listingIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<BookListing> selectEligibleListings(
            @Param("userId") long userId,
            @Param("listingIds") Collection<Long> listingIds,
            @Param("now") Instant now
    );

    @Select("""
            <script>
            """ + ELIGIBLE_SELECT + """
              <if test="excludedIds != null and !excludedIds.isEmpty()">
                AND l.id NOT IN
                <foreach collection="excludedIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </if>
              ORDER BY COALESCE((
                SELECT COUNT(*) FROM trade_behavior_event e
                WHERE e.listing_id = l.id AND e.action IN ('CART_ADD', 'ORDER_CREATED', 'PAYMENT_SUCCEEDED')
                  AND e.event_time >= DATE_SUB(#{now}, INTERVAL 30 DAY)
              ), 0) DESC, l.published_at DESC, l.id DESC
              LIMIT #{limit}
            </script>
            """)
    List<BookListing> selectFallbackListings(
            @Param("userId") long userId,
            @Param("excludedIds") Collection<Long> excludedIds,
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    @Insert("""
            INSERT INTO recommendation_delivery (
                request_id, user_id, listing_id, position, model_version, candidate_score,
                sources_json, reason_code, scene, data_source, served_at
            ) VALUES (
                #{requestId}, #{userId}, #{listingId}, #{position}, #{modelVersion}, #{score},
                #{sourcesJson}, #{reasonCode}, #{scene}, #{dataSource}, #{servedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDelivery(DeliveryRow delivery);

    @Select("""
            SELECT id AS id, request_id AS requestId, user_id AS userId, listing_id AS listingId,
                   position AS position, model_version AS modelVersion, candidate_score AS score,
                   sources_json AS sourcesJson, reason_code AS reasonCode, scene AS scene,
                   data_source AS dataSource, served_at AS servedAt
            FROM recommendation_delivery
            WHERE id = #{deliveryId} AND user_id = #{userId} AND listing_id = #{listingId}
            """)
    DeliveryRow selectDelivery(
            @Param("deliveryId") long deliveryId,
            @Param("userId") long userId,
            @Param("listingId") long listingId
    );

    @Select("""
            SELECT ci.id AS id, ci.listing_id AS listingId, l.title AS title, l.author AS author,
                   l.unit_price AS unitPrice, l.currency AS currency, l.status AS status,
                   s.available AS available, ci.recommendation_delivery_id AS recommendationDeliveryId,
                   ci.created_at AS createdAt, ci.updated_at AS updatedAt
            FROM cart_item ci
            JOIN book_listing l ON l.id = ci.listing_id
            JOIN sku_stock s ON s.listing_id = ci.listing_id
            WHERE ci.user_id = #{userId}
            ORDER BY ci.updated_at DESC, ci.id DESC
            """)
    List<CartItem> selectCartItems(long userId);

    @Insert("""
            INSERT IGNORE INTO cart_item (
                user_id, listing_id, recommendation_delivery_id, created_at, updated_at
            ) VALUES (#{userId}, #{listingId}, #{deliveryId}, #{now}, #{now})
            """)
    int insertCartItem(
            @Param("userId") long userId,
            @Param("listingId") long listingId,
            @Param("deliveryId") Long deliveryId,
            @Param("now") Instant now
    );

    @Delete("DELETE FROM cart_item WHERE user_id = #{userId} AND listing_id = #{listingId}")
    int deleteCartItem(@Param("userId") long userId, @Param("listingId") long listingId);

    @Insert("""
            INSERT INTO trade_behavior_event (
                event_id, user_id, listing_id, action, event_time, ingested_at,
                recommendation_delivery_id, request_id, session_id, position, source,
                model_version, order_no, refund_reason_code, schema_version, data_source
            ) VALUES (
                #{eventId}, #{userId}, #{listingId}, #{action}, #{eventTime}, #{ingestedAt},
                #{recommendationDeliveryId}, #{requestId}, #{sessionId}, #{position}, #{source},
                #{modelVersion}, #{orderNo}, #{refundReasonCode}, 1, #{dataSource}
            )
            """)
    int insertBehavior(TradeBehaviorEvent event);

    @Select("""
            SELECT o.buyer_id AS userId, i.listing_id AS listingId,
                   o.recommendation_delivery_id AS recommendationDeliveryId,
                   d.request_id AS requestId, d.position AS position,
                   JSON_UNQUOTE(JSON_EXTRACT(d.sources_json, '$[0]')) AS source,
                   d.model_version AS modelVersion
            FROM trade_order o
            JOIN order_item i ON i.order_id = o.id AND i.line_no = 1
            LEFT JOIN recommendation_delivery d ON d.id = o.recommendation_delivery_id
            WHERE o.order_no = #{orderNo}
            """)
    OrderBehaviorTarget selectOrderBehaviorTarget(String orderNo);

    /**
     * DeliveryRow 将 JSON 来源与领域集合的转换限制在基础设施层。
     *
     * @author ning
     * @date 2026-09-03
     */
    class DeliveryRow {
        public Long id;
        public String requestId;
        public long userId;
        public long listingId;
        public int position;
        public String modelVersion;
        public java.math.BigDecimal score;
        public String sourcesJson;
        public String reasonCode;
        public String scene;
        public String dataSource;
        public Instant servedAt;
    }
}
