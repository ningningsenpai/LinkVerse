ALTER TABLE trade_order
    ADD COLUMN payment_intent_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER currency,
    ADD UNIQUE KEY uk_trade_order_payment_intent (payment_intent_no);

CREATE TABLE consumed_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    consumer_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consumed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consumed_event (consumer_name, event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
