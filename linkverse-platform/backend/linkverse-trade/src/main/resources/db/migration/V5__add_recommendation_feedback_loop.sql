CREATE TABLE book_category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(128) NOT NULL,
    parent_id BIGINT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_book_category_code (code),
    KEY idx_book_category_parent (parent_id),
    CONSTRAINT fk_book_category_parent FOREIGN KEY (parent_id) REFERENCES book_category (id) ON DELETE RESTRICT,
    CONSTRAINT chk_book_category_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO book_category (code, name, parent_id, status)
VALUES ('UNCLASSIFIED', '未分类', NULL, 'ACTIVE');

ALTER TABLE book_listing
    ADD COLUMN category_id BIGINT NULL AFTER seller_id,
    ADD COLUMN published_at DATETIME(6) NULL AFTER status;

UPDATE book_listing
SET category_id = (SELECT id FROM book_category WHERE code = 'UNCLASSIFIED'),
    published_at = created_at
WHERE category_id IS NULL OR published_at IS NULL;

ALTER TABLE book_listing
    MODIFY COLUMN category_id BIGINT NOT NULL,
    MODIFY COLUMN published_at DATETIME(6) NOT NULL,
    ADD KEY idx_book_listing_recommendation (status, category_id, published_at, id),
    ADD KEY idx_book_listing_seller (seller_id, status, id),
    ADD CONSTRAINT fk_book_listing_category FOREIGN KEY (category_id) REFERENCES book_category (id) ON DELETE RESTRICT;

CREATE TABLE recommendation_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    listing_id BIGINT NOT NULL,
    position SMALLINT NOT NULL,
    model_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_score DECIMAL(18, 10) NOT NULL,
    sources_json JSON NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scene VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    data_source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REAL',
    served_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recommendation_delivery_item (request_id, listing_id),
    KEY idx_recommendation_delivery_user_time (user_id, served_at, id),
    KEY idx_recommendation_delivery_listing_time (listing_id, served_at, id),
    CONSTRAINT fk_recommendation_delivery_listing FOREIGN KEY (listing_id) REFERENCES book_listing (id) ON DELETE RESTRICT,
    CONSTRAINT chk_recommendation_delivery_position CHECK (position > 0),
    CONSTRAINT chk_recommendation_delivery_source CHECK (data_source IN ('REAL', 'SYNTHETIC'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE cart_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    listing_id BIGINT NOT NULL,
    recommendation_delivery_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cart_item_user_listing (user_id, listing_id),
    KEY idx_cart_item_user_updated (user_id, updated_at, id),
    CONSTRAINT fk_cart_item_listing FOREIGN KEY (listing_id) REFERENCES book_listing (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cart_item_delivery FOREIGN KEY (recommendation_delivery_id) REFERENCES recommendation_delivery (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE trade_behavior_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    listing_id BIGINT NOT NULL,
    action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_time DATETIME(6) NOT NULL,
    ingested_at DATETIME(6) NOT NULL,
    recommendation_delivery_id BIGINT NULL,
    request_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    session_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    position SMALLINT NULL,
    source VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    model_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    order_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    refund_reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    schema_version SMALLINT NOT NULL DEFAULT 1,
    data_source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REAL',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trade_behavior_event_id (event_id),
    KEY idx_trade_behavior_user_time (user_id, event_time, id),
    KEY idx_trade_behavior_listing_time (listing_id, event_time, id),
    KEY idx_trade_behavior_order_action (order_no, action, id),
    KEY idx_trade_behavior_snapshot_watermark (ingested_at, id),
    CONSTRAINT fk_trade_behavior_listing FOREIGN KEY (listing_id) REFERENCES book_listing (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trade_behavior_delivery FOREIGN KEY (recommendation_delivery_id) REFERENCES recommendation_delivery (id) ON DELETE RESTRICT,
    CONSTRAINT chk_trade_behavior_action CHECK (action IN (
        'IMPRESSION', 'DETAIL_OPEN', 'CART_ADD', 'ORDER_CREATED', 'PAYMENT_SUCCEEDED', 'REFUNDED'
    )),
    CONSTRAINT chk_trade_behavior_position CHECK (position IS NULL OR position > 0),
    CONSTRAINT chk_trade_behavior_schema CHECK (schema_version = 1),
    CONSTRAINT chk_trade_behavior_source CHECK (data_source IN ('REAL', 'SYNTHETIC'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE trade_order
    ADD COLUMN recommendation_delivery_id BIGINT NULL AFTER request_fingerprint,
    ADD KEY idx_trade_order_recommendation_delivery (recommendation_delivery_id),
    ADD CONSTRAINT fk_trade_order_recommendation_delivery
        FOREIGN KEY (recommendation_delivery_id) REFERENCES recommendation_delivery (id) ON DELETE RESTRICT;
