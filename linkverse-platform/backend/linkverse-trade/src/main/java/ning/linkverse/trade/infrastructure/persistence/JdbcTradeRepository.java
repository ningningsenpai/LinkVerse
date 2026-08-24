package ning.linkverse.trade.infrastructure.persistence;

import ning.linkverse.trade.domain.TradeRepository;
import ning.linkverse.trade.domain.listing.BookListing;
import ning.linkverse.trade.domain.order.DueOrderCandidate;
import ning.linkverse.trade.domain.order.NewOrder;
import ning.linkverse.trade.domain.order.OrderItemSnapshot;
import ning.linkverse.trade.domain.order.TradeOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JdbcTradeRepository 使用 Trade Schema 完成商品、库存和订单的最小 JDBC 访问。
 *
 * @author ning
 * @date 2026-08-24
 */
@Repository
public class JdbcTradeRepository implements TradeRepository {

    private static final String ORDER_SELECT = """
            SELECT o.id, o.order_no, o.buyer_id, o.seller_id, o.idempotency_key,
                   o.request_fingerprint, o.status, o.total_amount, o.currency,
                   o.expire_at, o.created_at,
                   i.listing_id, i.seller_id AS item_seller_id, i.listing_version,
                   i.listing_title, i.listing_author, i.unit_price, i.line_amount,
                   i.quantity, i.currency AS item_currency
            FROM trade_order o
            JOIN order_item i ON i.order_id = o.id AND i.line_no = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcTradeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<BookListing> findListing(long listingId) {
        return jdbcTemplate.query("""
                        SELECT l.id, l.seller_id, l.title, l.author, l.description,
                               l.unit_price, l.currency, l.status, l.version,
                               s.available, l.updated_at
                        FROM book_listing l
                        JOIN sku_stock s ON s.listing_id = l.id
                        WHERE l.id = ?
                        """, (rs, rowNumber) -> new BookListing(
                        rs.getLong("id"),
                        rs.getLong("seller_id"),
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getString("description"),
                        rs.getBigDecimal("unit_price"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getLong("version"),
                        rs.getInt("available"),
                        rs.getTimestamp("updated_at").toInstant()
                ), listingId).stream().findFirst();
    }

    @Override
    public Optional<TradeOrder> findByBuyerAndIdempotencyKey(long buyerId, String idempotencyKey) {
        return findOrder(ORDER_SELECT + " WHERE o.buyer_id = ? AND o.idempotency_key = ?", buyerId, idempotencyKey);
    }

    @Override
    public Optional<TradeOrder> findByOrderNoAndBuyer(String orderNo, long buyerId) {
        return findOrder(ORDER_SELECT + " WHERE o.order_no = ? AND o.buyer_id = ?", orderNo, buyerId);
    }

    @Override
    public long insertOrder(NewOrder order) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO trade_order (
                        order_no, buyer_id, seller_id, idempotency_key, request_fingerprint,
                        status, total_amount, currency, expire_at, version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'PENDING_PAYMENT', ?, ?, ?, 0, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, order.orderNo());
            statement.setLong(2, order.buyerId());
            statement.setLong(3, order.sellerId());
            statement.setString(4, order.idempotencyKey());
            statement.setString(5, order.requestFingerprint());
            statement.setBigDecimal(6, order.totalAmount());
            statement.setString(7, order.currency());
            statement.setTimestamp(8, Timestamp.from(order.expireAt()));
            statement.setTimestamp(9, Timestamp.from(order.now()));
            statement.setTimestamp(10, Timestamp.from(order.now()));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建订单后未取得主键");
        }
        return key.longValue();
    }

    @Override
    public boolean decrementStock(long listingId, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE sku_stock
                        SET available = available - 1, version = version + 1, updated_at = ?
                        WHERE listing_id = ? AND available >= 1
                        """, Timestamp.from(now), listingId) == 1;
    }

    @Override
    public void insertOrderItem(long orderId, NewOrder order) {
        OrderItemSnapshot item = order.item();
        jdbcTemplate.update("""
                        INSERT INTO order_item (
                            order_id, line_no, listing_id, seller_id, listing_version,
                            listing_title, listing_author, unit_price, line_amount,
                            quantity, currency, created_at
                        ) VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                orderId,
                item.listingId(),
                item.sellerId(),
                item.listingVersion(),
                item.listingTitle(),
                item.listingAuthor(),
                item.unitPrice(),
                item.lineAmount(),
                item.quantity(),
                item.currency(),
                Timestamp.from(order.now())
        );
    }

    @Override
    public List<DueOrderCandidate> findDuePending(
            Instant cutoff,
            Instant afterExpireAt,
            Long afterId,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, order_no, expire_at
                FROM trade_order
                WHERE status = 'PENDING_PAYMENT' AND expire_at <= ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(Timestamp.from(cutoff));
        if (afterExpireAt != null && afterId != null) {
            sql.append(" AND (expire_at > ? OR (expire_at = ? AND id > ?))");
            parameters.add(Timestamp.from(afterExpireAt));
            parameters.add(Timestamp.from(afterExpireAt));
            parameters.add(afterId);
        }
        sql.append(" ORDER BY expire_at, id LIMIT ?");
        parameters.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNumber) -> new DueOrderCandidate(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getTimestamp("expire_at").toInstant()
        ), parameters.toArray());
    }

    private Optional<TradeOrder> findOrder(String sql, Object... parameters) {
        return jdbcTemplate.query(sql, (rs, rowNumber) -> new TradeOrder(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getLong("buyer_id"),
                rs.getLong("seller_id"),
                rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"),
                rs.getString("status"),
                rs.getBigDecimal("total_amount"),
                rs.getString("currency"),
                rs.getTimestamp("expire_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                new OrderItemSnapshot(
                        rs.getLong("listing_id"),
                        rs.getLong("item_seller_id"),
                        rs.getLong("listing_version"),
                        rs.getString("listing_title"),
                        rs.getString("listing_author"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("line_amount"),
                        rs.getInt("quantity"),
                        rs.getString("item_currency")
                )
        ), parameters).stream().findFirst();
    }
}
