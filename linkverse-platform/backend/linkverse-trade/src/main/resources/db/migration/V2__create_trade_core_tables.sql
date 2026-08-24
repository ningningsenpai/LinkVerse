CREATE TABLE book_listing (
    id BIGINT NOT NULL AUTO_INCREMENT,
    seller_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    author VARCHAR(128) NOT NULL,
    description VARCHAR(2000) NULL,
    unit_price DECIMAL(19, 4) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_book_listing_price CHECK (unit_price > 0),
    CONSTRAINT chk_book_listing_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_book_listing_status CHECK (status IN ('ON_SALE', 'OFF_SALE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE sku_stock (
    listing_id BIGINT NOT NULL,
    available INT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (listing_id),
    CONSTRAINT fk_sku_stock_listing FOREIGN KEY (listing_id) REFERENCES book_listing (id) ON DELETE RESTRICT,
    CONSTRAINT chk_sku_stock_available CHECK (available >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE trade_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    total_amount DECIMAL(19, 4) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expire_at DATETIME(6) NOT NULL,
    paid_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_trade_order_no (order_no),
    UNIQUE KEY uk_trade_order_buyer_idempotency (buyer_id, idempotency_key),
    KEY idx_trade_order_due (status, expire_at, id),
    CONSTRAINT chk_trade_order_amount CHECK (total_amount > 0),
    CONSTRAINT chk_trade_order_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_trade_order_status CHECK (status IN ('PENDING_PAYMENT', 'CLOSING', 'PAID', 'CLOSED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE order_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    line_no SMALLINT NOT NULL,
    listing_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    listing_version BIGINT NOT NULL,
    listing_title VARCHAR(200) NOT NULL,
    listing_author VARCHAR(128) NOT NULL,
    unit_price DECIMAL(19, 4) NOT NULL,
    line_amount DECIMAL(19, 4) NOT NULL,
    quantity INT NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_item_line (order_id, line_no),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES trade_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_item_listing FOREIGN KEY (listing_id) REFERENCES book_listing (id) ON DELETE RESTRICT,
    CONSTRAINT chk_order_item_quantity CHECK (quantity = 1),
    CONSTRAINT chk_order_item_amount CHECK (unit_price > 0 AND line_amount > 0),
    CONSTRAINT chk_order_item_currency CHECK (currency REGEXP '^[A-Z]{3}$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
