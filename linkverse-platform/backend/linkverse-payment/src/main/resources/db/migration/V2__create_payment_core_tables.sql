CREATE TABLE payment_intent (
    id BIGINT NOT NULL AUTO_INCREMENT,
    intent_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_txn_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expire_at DATETIME(6) NOT NULL,
    succeeded_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    refunded_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_intent_no (intent_no),
    UNIQUE KEY uk_payment_intent_order (order_no),
    UNIQUE KEY uk_payment_provider_txn (provider, provider_txn_no),
    KEY idx_payment_intent_status_expire (status, expire_at, id),
    CONSTRAINT chk_payment_intent_amount CHECK (amount > 0),
    CONSTRAINT chk_payment_intent_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_payment_intent_status CHECK (
        status IN ('PENDING', 'SUCCEEDED', 'CLOSED', 'REFUND_PENDING', 'REFUNDED')
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE payment_callback_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    notification_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_txn_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    intent_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    received_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_callback_notification (provider, notification_id),
    UNIQUE KEY uk_payment_callback_transaction (provider, provider_txn_no),
    KEY idx_payment_callback_intent (intent_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE payment_exception (
    id BIGINT NOT NULL AUTO_INCREMENT,
    exception_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    intent_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    exception_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    detail_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_exception_no (exception_no),
    KEY idx_payment_exception_intent (intent_no, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE refund_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_request_no VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    intent_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_txn_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_request_no (refund_request_no),
    KEY idx_refund_intent_status (intent_no, status),
    CONSTRAINT chk_refund_amount CHECK (amount > 0),
    CONSTRAINT chk_refund_status CHECK (status IN ('PENDING', 'UNKNOWN', 'SUCCEEDED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    exchange_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    routing_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    locked_until DATETIME(6) NULL,
    last_error_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_delivery (status, next_attempt_at, id),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'SENDING', 'PUBLISHED', 'PARKED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
