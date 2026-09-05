CREATE TABLE merchant_price_draft (
  draft_id CHAR(36) NOT NULL PRIMARY KEY,
  operator_subject VARCHAR(128) NOT NULL,
  session_id VARCHAR(128) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  intent_hash CHAR(64) NOT NULL,
  currency CHAR(3) NOT NULL,
  state ENUM('PREPARED', 'APPLIED', 'CANCELLED', 'REJECTED') NOT NULL,
  items JSON NOT NULL,
  result JSON NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  resolved_at TIMESTAMP(6) NULL,
  CONSTRAINT uq_merchant_price_draft_request
    UNIQUE (operator_subject, session_id, request_key),
  CONSTRAINT chk_merchant_price_draft_items CHECK (
    JSON_TYPE(items) = 'ARRAY' AND JSON_LENGTH(items) > 0
  ),
  CONSTRAINT chk_merchant_price_draft_resolution CHECK (
    (state = 'PREPARED' AND resolved_at IS NULL AND result IS NULL)
    OR (state IN ('APPLIED', 'CANCELLED', 'REJECTED')
      AND resolved_at IS NOT NULL AND resolved_at >= created_at
      AND result IS NOT NULL AND JSON_TYPE(result) = 'OBJECT')
  ),
  INDEX idx_merchant_price_draft_owner_created (operator_subject, created_at, draft_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- The migration identity owns these views. Analytics accounts receive SELECT on the views only.
CREATE SQL SECURITY DEFINER VIEW merchant_products AS
SELECT p.product_id, p.name, p.price_minor, p.currency, p.publication_version,
       p.available, p.publication_state, p.stock_quantity,
       (p.publication_state = 'PUBLISHED' AND p.available
        AND NOT EXISTS (SELECT 1 FROM seckill_activity a WHERE a.product_id = p.product_id))
         AS price_editable
FROM product p;

-- Gross paid sales use the immutable order price; subsequent repricing cannot rewrite history.
CREATE SQL SECURITY DEFINER VIEW merchant_paid_orders AS
SELECT 'STANDARD' AS order_kind, o.order_id, o.product_id, o.product_name,
       o.quantity, o.total_price_minor, o.currency, a.succeeded_at
FROM standard_order o
JOIN mock_payment_attempt a ON a.order_kind = 'STANDARD' AND a.order_id = o.order_id
WHERE o.status = 'PAID' AND o.sandbox_id IS NULL AND a.sandbox_id IS NULL
  AND a.state = 'SUCCEEDED' AND a.succeeded_at IS NOT NULL
  AND a.user_subject = o.user_subject
  AND a.amount_minor = o.total_price_minor AND a.currency = o.currency
UNION ALL
SELECT 'SECKILL' AS order_kind, o.order_id, o.product_id, o.product_name,
       o.quantity, o.total_price_minor, o.currency, a.succeeded_at
FROM seckill_order o
JOIN mock_payment_attempt a ON a.order_kind = 'SECKILL' AND a.order_id = o.order_id
WHERE o.status = 'PAID' AND a.sandbox_id IS NULL
  AND a.state = 'SUCCEEDED' AND a.succeeded_at IS NOT NULL
  AND a.user_subject = o.user_subject
  AND a.amount_minor = o.total_price_minor AND a.currency = o.currency;

-- Reporting connections use UTC, matching payment and fixture timestamps.
CREATE SQL SECURITY DEFINER VIEW merchant_daily_sales AS
SELECT DATE(succeeded_at) AS sale_date, product_id, currency,
       SUM(total_price_minor) AS amount_minor, COUNT(*) AS order_count, SUM(quantity) AS units
FROM merchant_paid_orders
GROUP BY DATE(succeeded_at), product_id, currency;
