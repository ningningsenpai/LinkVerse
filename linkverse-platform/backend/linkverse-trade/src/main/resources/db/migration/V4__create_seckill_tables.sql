CREATE TABLE seckill_campaign (
    id BIGINT NOT NULL AUTO_INCREMENT,
    campaign_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    initial_stock INT NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_seckill_campaign_no (campaign_no),
    UNIQUE KEY uk_seckill_campaign_listing (listing_id),
    CONSTRAINT fk_seckill_campaign_listing FOREIGN KEY (listing_id) REFERENCES book_listing (id) ON DELETE RESTRICT,
    CONSTRAINT chk_seckill_campaign_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT chk_seckill_campaign_stock CHECK (initial_stock >= 0),
    CONSTRAINT chk_seckill_campaign_window CHECK (ends_at > starts_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE seckill_reservation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    campaign_id BIGINT NOT NULL,
    campaign_version BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_seckill_reservation_no (reservation_no),
    UNIQUE KEY uk_seckill_reservation_event (event_id),
    UNIQUE KEY uk_seckill_reservation_user (campaign_id, user_id),
    UNIQUE KEY uk_seckill_reservation_order (order_no),
    KEY idx_seckill_reservation_status (status, updated_at, id),
    CONSTRAINT fk_seckill_reservation_campaign FOREIGN KEY (campaign_id) REFERENCES seckill_campaign (id) ON DELETE RESTRICT,
    CONSTRAINT chk_seckill_reservation_status CHECK (status IN (
        'PUBLISH_PENDING', 'PUBLISHED', 'ORDER_CREATED', 'FAILED', 'EXPIRED', 'COMMITTED', 'RELEASED'
    ))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE trade_order
    ADD COLUMN reservation_id BIGINT NULL AFTER payment_intent_no,
    ADD UNIQUE KEY uk_trade_order_reservation (reservation_id),
    ADD CONSTRAINT fk_trade_order_reservation FOREIGN KEY (reservation_id)
        REFERENCES seckill_reservation (id) ON DELETE RESTRICT;

CREATE TABLE trade_outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    exchange_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    routing_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    locked_until DATETIME(6) NULL,
    published_at DATETIME(6) NULL,
    last_error_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trade_outbox_event (event_id),
    KEY idx_trade_outbox_due (status, next_attempt_at, id),
    CONSTRAINT chk_trade_outbox_status CHECK (status IN ('PENDING', 'SENDING', 'PUBLISHED', 'PARKED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
