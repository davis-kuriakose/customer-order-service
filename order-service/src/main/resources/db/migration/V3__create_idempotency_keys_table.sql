CREATE TABLE idempotency_keys
(
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    order_id        UUID,
    response_status INTEGER      NOT NULL,
    response_body   TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_idempotency_keys     PRIMARY KEY (idempotency_key),
    CONSTRAINT fk_idempotency_order_id FOREIGN KEY (order_id) REFERENCES orders (id)
);
