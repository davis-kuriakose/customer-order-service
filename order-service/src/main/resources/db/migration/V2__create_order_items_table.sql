CREATE TABLE order_items
(
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    order_id            UUID         NOT NULL,
    product_offering_id VARCHAR(255) NOT NULL,
    quantity            INTEGER      NOT NULL,

    CONSTRAINT pk_order_items       PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT chk_order_items_qty  CHECK (quantity >= 1)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
