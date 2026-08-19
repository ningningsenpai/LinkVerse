CREATE TABLE user_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    normalized_username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_user_account_normalized_username UNIQUE (normalized_username),
    CONSTRAINT ck_user_account_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE auth_client (
    id BIGINT NOT NULL AUTO_INCREMENT,
    registered_client_id CHAR(36) NOT NULL,
    client_id VARCHAR(100) NOT NULL,
    client_secret_hash VARCHAR(255) NOT NULL,
    client_name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL,
    audience VARCHAR(100) NOT NULL,
    scopes JSON NOT NULL,
    access_token_ttl_seconds INT NOT NULL,
    secret_expires_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_client_registered_client_id UNIQUE (registered_client_id),
    CONSTRAINT uk_auth_client_client_id UNIQUE (client_id),
    CONSTRAINT ck_auth_client_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_auth_client_scopes_json CHECK (JSON_TYPE(scopes) = 'ARRAY'),
    CONSTRAINT ck_auth_client_token_ttl CHECK (access_token_ttl_seconds BETWEEN 60 AND 3600)
);
