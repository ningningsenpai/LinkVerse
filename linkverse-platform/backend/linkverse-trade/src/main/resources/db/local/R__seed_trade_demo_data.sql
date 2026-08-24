INSERT INTO book_listing (
    id, seller_id, title, author, description, unit_price, currency, status, version
) VALUES
    (10001, 9000001, '领域驱动设计', 'Eric Evans', '普通交易演示商品', 68.0000, 'CNY', 'ON_SALE', 0),
    (10002, 9000001, '高性能 MySQL', 'Baron Schwartz', '后续秒杀活动专用商品', 88.0000, 'CNY', 'ON_SALE', 0)
ON DUPLICATE KEY UPDATE id = book_listing.id;

INSERT INTO sku_stock (listing_id, available, version)
VALUES (10001, 10, 0), (10002, 100, 0)
ON DUPLICATE KEY UPDATE listing_id = sku_stock.listing_id;
