package ning.linkverse.trade.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ning.linkverse.trade.domain.listing.BookListing;
import ning.linkverse.trade.domain.order.DueOrderCandidate;
import ning.linkverse.trade.domain.order.NewOrder;
import ning.linkverse.trade.domain.order.OrderItemSnapshot;
import ning.linkverse.trade.domain.order.PayableOrder;
import ning.linkverse.trade.domain.order.TradeOrder;
import ning.linkverse.trade.infrastructure.persistence.entity.TradeOrderEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * TradeMapper 提供商品、库存和订单的 MyBatis-Plus 数据访问入口。
 *
 * @author ning
 * @date 2026-08-31
 */
@Mapper
public interface TradeMapper extends BaseMapper<TradeOrderEntity> {

    String ORDER_SELECT = """
            SELECT o.id AS id, o.order_no AS orderNo, o.buyer_id AS buyerId,
                   o.seller_id AS sellerId, o.idempotency_key AS idempotencyKey,
                   o.request_fingerprint AS requestFingerprint, o.status AS status,
                   o.total_amount AS totalAmount, o.currency AS currency,
                   o.expire_at AS expireAt, o.created_at AS createdAt,
                   i.listing_id AS listingId, i.seller_id AS itemSellerId,
                   i.listing_version AS listingVersion, i.listing_title AS listingTitle,
                   i.listing_author AS listingAuthor, i.unit_price AS unitPrice,
                   i.line_amount AS lineAmount, i.quantity AS quantity,
                   i.currency AS itemCurrency
            FROM trade_order o
            JOIN order_item i ON i.order_id = o.id AND i.line_no = 1
            """;

    @Select("""
            SELECT l.id AS id, l.seller_id AS sellerId, l.category_id AS categoryId,
                   c.code AS categoryCode, l.title AS title,
                   l.author AS author, l.description AS description,
                   l.unit_price AS unitPrice, l.currency AS currency,
                   l.status AS status, l.published_at AS publishedAt, l.version AS version,
                   s.available AS available, l.updated_at AS updatedAt
            FROM book_listing l
            JOIN book_category c ON c.id = l.category_id
            JOIN sku_stock s ON s.listing_id = l.id
            WHERE l.id = #{listingId}
            """)
    BookListing selectListing(long listingId);

    @Select(ORDER_SELECT + " WHERE o.buyer_id = #{buyerId} AND o.idempotency_key = #{idempotencyKey}")
    TradeOrderRow selectOrderByBuyerAndIdempotencyKey(
            @Param("buyerId") long buyerId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Select(ORDER_SELECT + " WHERE o.order_no = #{orderNo} AND o.buyer_id = #{buyerId}")
    TradeOrderRow selectOrderByNoAndBuyer(@Param("orderNo") String orderNo, @Param("buyerId") long buyerId);

    @Select("""
            SELECT order_no AS orderNo, buyer_id AS buyerId, seller_id AS sellerId,
                   total_amount AS amount, currency AS currency, status AS status,
                   expire_at AS expireAt, payment_intent_no AS paymentIntentNo
            FROM trade_order
            WHERE order_no = #{orderNo}
            """)
    PayableOrder selectPayableByOrder(String orderNo);

    @Select("""
            SELECT order_no AS orderNo, buyer_id AS buyerId, seller_id AS sellerId,
                   total_amount AS amount, currency AS currency, status AS status,
                   expire_at AS expireAt, payment_intent_no AS paymentIntentNo
            FROM trade_order
            WHERE order_no = #{orderNo} AND buyer_id = #{buyerId}
            """)
    PayableOrder selectPayableByOrderAndBuyer(@Param("orderNo") String orderNo, @Param("buyerId") long buyerId);

    @Update("""
            UPDATE sku_stock
            SET available = available - 1, version = version + 1, updated_at = #{now}
            WHERE listing_id = #{listingId} AND available >= 1
            """)
    int decrementStock(@Param("listingId") long listingId, @Param("now") Instant now);

    @Select("""
            SELECT COUNT(*) FROM seckill_campaign
            WHERE listing_id = #{listingId} AND status = 'ENABLED'
              AND starts_at <= #{now} AND ends_at > #{now}
            """)
    int countActiveSeckillCampaign(@Param("listingId") long listingId, @Param("now") Instant now);

    @Insert("""
            INSERT INTO order_item (
                order_id, line_no, listing_id, seller_id, listing_version,
                listing_title, listing_author, unit_price, line_amount,
                quantity, currency, created_at
            ) VALUES (
                #{orderId}, 1, #{order.item.listingId}, #{order.item.sellerId},
                #{order.item.listingVersion}, #{order.item.listingTitle},
                #{order.item.listingAuthor}, #{order.item.unitPrice},
                #{order.item.lineAmount}, #{order.item.quantity},
                #{order.item.currency}, #{order.now}
            )
            """)
    int insertOrderItem(@Param("orderId") long orderId, @Param("order") NewOrder order);

    @Update("""
            UPDATE trade_order
            SET reservation_id = #{reservationId}, version = version + 1, updated_at = #{now}
            WHERE order_no = #{orderNo} AND reservation_id IS NULL
            """)
    int attachReservation(
            @Param("orderNo") String orderNo,
            @Param("reservationId") long reservationId,
            @Param("now") Instant now
    );

    @Update("""
            UPDATE trade_order
            SET payment_intent_no = #{intentNo}, version = version + 1, updated_at = #{now}
            WHERE order_no = #{orderNo} AND buyer_id = #{buyerId} AND status = 'PENDING_PAYMENT'
              AND (payment_intent_no IS NULL OR payment_intent_no = #{intentNo})
            """)
    int attachPaymentIntent(
            @Param("orderNo") String orderNo,
            @Param("buyerId") long buyerId,
            @Param("intentNo") String intentNo,
            @Param("now") Instant now
    );

    @Update("""
            UPDATE trade_order
            SET status = 'CLOSING', version = version + 1, updated_at = #{now}
            WHERE order_no = #{orderNo} AND status = 'PENDING_PAYMENT' AND expire_at <= #{now}
            """)
    int claimClosing(@Param("orderNo") String orderNo, @Param("now") Instant now);

    @Update("""
            UPDATE trade_order
            SET status = 'PENDING_PAYMENT', version = version + 1, updated_at = #{now}
            WHERE order_no = #{orderNo} AND status = 'CLOSING'
            """)
    int releaseClosing(@Param("orderNo") String orderNo, @Param("now") Instant now);

    @Update("""
            UPDATE trade_order
            SET status = 'PAID', payment_intent_no = #{intentNo}, paid_at = #{paidAt},
                version = version + 1, updated_at = #{paidAt}
            WHERE order_no = #{orderNo} AND status IN ('PENDING_PAYMENT', 'CLOSING')
              AND (payment_intent_no IS NULL OR payment_intent_no = #{intentNo})
            """)
    int markPaid(
            @Param("orderNo") String orderNo,
            @Param("intentNo") String intentNo,
            @Param("paidAt") Instant paidAt
    );

    @Select("""
            SELECT i.listing_id
            FROM trade_order o
            JOIN order_item i ON i.order_id = o.id AND i.line_no = 1
            WHERE o.order_no = #{orderNo} AND o.status = 'CLOSING'
            FOR UPDATE
            """)
    Long selectClosingListingForUpdate(String orderNo);

    @Update("""
            UPDATE trade_order
            SET status = 'CLOSED', closed_at = #{closedAt}, version = version + 1,
                updated_at = #{closedAt}
            WHERE order_no = #{orderNo} AND status = 'CLOSING'
            """)
    int closeOrder(@Param("orderNo") String orderNo, @Param("closedAt") Instant closedAt);

    @Update("""
            UPDATE sku_stock
            SET available = available + 1, version = version + 1, updated_at = #{now}
            WHERE listing_id = #{listingId}
            """)
    int restoreStock(@Param("listingId") long listingId, @Param("now") Instant now);

    @Insert("""
            INSERT INTO consumed_event (consumer_name, event_id, event_type, consumed_at)
            VALUES (#{consumerName}, #{eventId}, #{eventType}, #{consumedAt})
            """)
    int insertConsumedEvent(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("consumedAt") Instant consumedAt
    );

    @Select("""
            <script>
            SELECT id AS id, order_no AS orderNo, expire_at AS expireAt
            FROM trade_order
            WHERE (
                (status = 'PENDING_PAYMENT' AND expire_at &lt;= #{cutoff})
                OR (status = 'CLOSING' AND updated_at &lt;= DATE_SUB(#{cutoff}, INTERVAL 30 SECOND))
            )
            <if test="afterExpireAt != null and afterId != null">
              AND (expire_at &gt; #{afterExpireAt}
                   OR (expire_at = #{afterExpireAt} AND id &gt; #{afterId}))
            </if>
            ORDER BY expire_at, id
            LIMIT #{limit}
            </script>
            """)
    List<DueOrderCandidate> selectDuePending(
            @Param("cutoff") Instant cutoff,
            @Param("afterExpireAt") Instant afterExpireAt,
            @Param("afterId") Long afterId,
            @Param("limit") int limit
    );

    /**
     * TradeOrderRow 承接订单与单行商品快照的联表结果，并在基础设施边界转换为聚合。
     *
     * @author ning
     * @date 2026-08-31
     */
    record TradeOrderRow(
            long id,
            String orderNo,
            long buyerId,
            long sellerId,
            String idempotencyKey,
            String requestFingerprint,
            String status,
            BigDecimal totalAmount,
            String currency,
            Instant expireAt,
            Instant createdAt,
            long listingId,
            long itemSellerId,
            long listingVersion,
            String listingTitle,
            String listingAuthor,
            BigDecimal unitPrice,
            BigDecimal lineAmount,
            int quantity,
            String itemCurrency
    ) {
        public TradeOrder toDomain() {
            return new TradeOrder(
                    id,
                    orderNo,
                    buyerId,
                    sellerId,
                    idempotencyKey,
                    requestFingerprint,
                    status,
                    totalAmount,
                    currency,
                    expireAt,
                    createdAt,
                    new OrderItemSnapshot(
                            listingId,
                            itemSellerId,
                            listingVersion,
                            listingTitle,
                            listingAuthor,
                            unitPrice,
                            lineAmount,
                            quantity,
                            itemCurrency
                    )
            );
        }
    }
}
