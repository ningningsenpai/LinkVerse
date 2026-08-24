INSERT INTO book_listing (
    id, seller_id, title, author, description, unit_price, currency, status, version
) VALUES
    (10001, 9000001, '领域驱动设计', 'Eric Evans', '普通交易演示商品', 68.0000, 'CNY', 'ON_SALE', 0),
    (10002, 9000001, '高性能 MySQL', 'Baron Schwartz', '后续秒杀活动专用商品', 88.0000, 'CNY', 'ON_SALE', 0)
ON DUPLICATE KEY UPDATE id = book_listing.id;

INSERT INTO sku_stock (listing_id, available, version)
VALUES (10001, 10, 0), (10002, 100, 0)
ON DUPLICATE KEY UPDATE listing_id = sku_stock.listing_id;

INSERT INTO seckill_campaign (
    id, campaign_no, listing_id, version, status, initial_stock, starts_at, ends_at
) VALUES (
    20001, '00000000000000000000000000020001', 10002, 1, 'ENABLED', 100,
    '2026-01-01 00:00:00.000000', '2036-01-01 00:00:00.000000'
)
ON DUPLICATE KEY UPDATE id = seckill_campaign.id;

INSERT INTO seckill_campaign (
    id, campaign_no, listing_id, version, status, initial_stock, starts_at, ends_at
) VALUES (
    20001, '00000000000000000000000000020001', 10002, 1, 'ENABLED', 100,
    '2026-01-01 00:00:00.000000', '2036-01-01 00:00:00.000000'
)
ON DUPLICATE KEY UPDATE id = seckill_campaign.id;
