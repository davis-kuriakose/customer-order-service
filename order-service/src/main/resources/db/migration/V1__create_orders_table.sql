CREATE TABLE orders
(
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    state       VARCHAR(20)  NOT NULL,
    category    VARCHAR(10)  NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    site_id     VARCHAR(255) NOT NULL,
    pm_type     VARCHAR(20)  NOT NULL,
    pm_iban     VARCHAR(50),
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_orders          PRIMARY KEY (id),
    CONSTRAINT chk_orders_state   CHECK (state    IN ('DRAFT', 'PREVIEW', 'SUBMITTED', 'CONFIRMED')),
    CONSTRAINT chk_orders_cat     CHECK (category IN ('B2B', 'B2C')),
    CONSTRAINT chk_orders_pm_type CHECK (pm_type  IN ('DIRECT_DEBIT', 'INVOICE')),
    CONSTRAINT chk_orders_iban    CHECK (pm_type != 'DIRECT_DEBIT' OR pm_iban IS NOT NULL)
);

CREATE INDEX idx_orders_category   ON orders (category);
CREATE INDEX idx_orders_created_at ON orders (created_at DESC);
