-- Missing indexes identified in performance review.
-- orders.state     — enables filtering by lifecycle stage (e.g. "all SUBMITTED orders")
-- orders.customer_id — natural query axis for customer-scoped lookups
-- idempotency_keys.order_id — FK column; PostgreSQL does NOT auto-index FK references
CREATE INDEX idx_orders_state        ON orders (state);
CREATE INDEX idx_orders_customer_id  ON orders (customer_id);
CREATE INDEX idx_idempotency_order_id ON idempotency_keys (order_id);
