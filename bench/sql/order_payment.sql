-- The runner supplies only
-- @citybuddy_sha, @label, @user_prefix and @products_json from its exact fixture snapshot.
-- Snapshot each product as {productId,unitPriceMinor,currency,productVersion,initialStock}.
SELECT @citybuddy_sha AS citybuddy_commit, @label AS benchmark_label, UTC_TIMESTAMP(6) AS observed_at;
SET SESSION time_zone='+00:00';
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION READ ONLY;

WITH fixture AS (
  SELECT * FROM JSON_TABLE(@products_json, '$[*]' COLUMNS (
    product_id VARCHAR(64) PATH '$.productId', initial_stock BIGINT PATH '$.initialStock',
    unit_price_minor BIGINT PATH '$.unitPriceMinor', currency CHAR(3) PATH '$.currency',
    product_version BIGINT PATH '$.productVersion'
  )) AS source
), created AS (
  SELECT o.product_id,COUNT(*) AS orders,SUM(o.quantity) AS units,
    SUM(o.status='PAID') AS paid_orders,SUM(o.total_price_minor) AS order_amount
  FROM standard_order o JOIN fixture f ON BINARY f.product_id=BINARY o.product_id GROUP BY o.product_id
)
SELECT f.product_id,f.initial_stock,p.stock_quantity,COALESCE(c.orders,0) AS created_orders,
  COALESCE(c.units,0) AS created_units,COALESCE(c.paid_orders,0) AS paid_orders,
  f.initial_stock-p.stock_quantity-COALESCE(c.units,0) AS inventory_difference,
  p.price_minor,p.currency,p.publication_version,f.unit_price_minor,f.currency AS fixture_currency,
  f.product_version,p.publication_state,p.available
FROM fixture f LEFT JOIN product p ON BINARY p.product_id=BINARY f.product_id
LEFT JOIN created c ON BINARY c.product_id=BINARY f.product_id ORDER BY f.product_id;

WITH fixture AS (
  SELECT product_id FROM JSON_TABLE(@products_json, '$[*]' COLUMNS(product_id VARCHAR(64) PATH '$.productId')) AS source
), cohort AS (
  SELECT o.* FROM standard_order o JOIN fixture f ON BINARY f.product_id=BINARY o.product_id
)
SELECT o.status,o.state_version,a.order_kind,a.state AS attempt_state,a.state_version AS attempt_version,
  c.requested_outcome,c.result_state,l.movement_type,x.event_type,x.publication_state,COUNT(*) AS rows_observed,
  COUNT(DISTINCT o.order_id) AS orders,COUNT(DISTINCT i.order_id) AS order_keys,
  COUNT(DISTINCT a.attempt_id) AS attempts,COUNT(DISTINCT c.callback_event_id) AS callbacks,
  COUNT(DISTINCT l.movement_id) AS payment_ledger,COUNT(DISTINCT x.event_id) AS creation_outbox
FROM cohort o LEFT JOIN order_idempotency i ON i.order_id=o.order_id
LEFT JOIN mock_payment_attempt a ON a.order_id=o.order_id AND a.order_kind='STANDARD'
LEFT JOIN mock_payment_callback c ON c.attempt_id=a.attempt_id
LEFT JOIN inventory_ledger l ON l.order_id=o.order_id
LEFT JOIN commerce_outbox x ON x.aggregate_id=o.order_id AND x.aggregate_type='STANDARD_ORDER'
GROUP BY o.status,o.state_version,a.order_kind,a.state,a.state_version,c.requested_outcome,c.result_state,
  l.movement_type,x.event_type,x.publication_state;

-- Money sums are independent aggregates, never multiplied by receipt/outbox joins.
WITH fixture AS (
  SELECT product_id FROM JSON_TABLE(@products_json, '$[*]' COLUMNS(product_id VARCHAR(64) PATH '$.productId')) AS source
), cohort AS (
  SELECT o.* FROM standard_order o JOIN fixture f ON BINARY f.product_id=BINARY o.product_id
)
SELECT
 (SELECT COUNT(*) FROM cohort) AS orders,
 (SELECT COUNT(*) FROM cohort WHERE status='PAID') AS paid_orders,
 (SELECT COALESCE(SUM(total_price_minor),0) FROM cohort WHERE status='PAID') AS paid_amount_minor,
 (SELECT COUNT(*) FROM mock_payment_attempt a JOIN cohort o ON o.order_id=a.order_id WHERE a.order_kind='STANDARD' AND a.state='SUCCEEDED') AS successful_attempts,
 (SELECT COALESCE(SUM(a.amount_minor),0) FROM mock_payment_attempt a JOIN cohort o ON o.order_id=a.order_id WHERE a.order_kind='STANDARD' AND a.state='SUCCEEDED') AS successful_attempt_amount_minor,
 (SELECT COUNT(*) FROM inventory_ledger l JOIN cohort o ON o.order_id=l.order_id WHERE l.movement_type='STANDARD_PAYMENT') AS payment_ledger,
 (SELECT COALESCE(SUM(l.payment_amount_minor),0) FROM inventory_ledger l JOIN cohort o ON o.order_id=l.order_id WHERE l.movement_type='STANDARD_PAYMENT') AS payment_ledger_amount_minor,
 (SELECT COUNT(*) FROM shopping_checkout_order c JOIN cohort o ON o.order_id=c.order_id) AS checkout_links,
 (SELECT COUNT(*) FROM mock_refund r JOIN cohort o ON o.order_id=r.order_id) AS refunds;

WITH fixture AS (
  SELECT * FROM JSON_TABLE(@products_json, '$[*]' COLUMNS (
    product_id VARCHAR(64) PATH '$.productId', unit_price_minor BIGINT PATH '$.unitPriceMinor',
    currency CHAR(3) PATH '$.currency', product_version BIGINT PATH '$.productVersion'
  )) AS source
), cohort AS (
  SELECT o.*,f.unit_price_minor AS fixture_price,f.currency AS fixture_currency,
    f.product_version AS fixture_version FROM standard_order o JOIN fixture f ON BINARY f.product_id=BINARY o.product_id
)
SELECT COUNT(*) AS inspected_rows,
  SUM(i.order_id IS NULL) AS missing_order_key,
  SUM(i.order_id IS NOT NULL AND (BINARY i.user_subject<>BINARY o.user_subject
    OR BINARY LEFT(i.idempotency_key,CHAR_LENGTH(CONCAT(@label,':o:')))<>BINARY CONCAT(@label,':o:')
    OR BINARY o.user_subject<>BINARY CONCAT(@user_prefix,SUBSTRING_INDEX(i.idempotency_key,':',-1)))) AS bad_owner_or_key,
  SUM(o.sandbox_id IS NOT NULL OR o.evaluation_owner_handle IS NOT NULL OR o.quantity<>1
    OR o.unit_price_minor<>o.fixture_price OR o.total_price_minor<>o.unit_price_minor*o.quantity
    OR BINARY o.currency<>BINARY o.fixture_currency OR o.product_version<>o.fixture_version) AS bad_order_truth,
  SUM(a.attempt_id IS NULL) AS missing_payment_attempt,
  SUM(a.attempt_id IS NOT NULL AND (BINARY a.user_subject<>BINARY o.user_subject
    OR a.amount_minor<>o.total_price_minor OR BINARY a.currency<>BINARY o.currency
    OR a.sandbox_id IS NOT NULL OR a.state<>'SUCCEEDED' OR a.state_version<>2 OR a.succeeded_at IS NULL
    OR BINARY a.request_idempotency_key<>BINARY CONCAT(@label,':p:',SUBSTRING_INDEX(i.idempotency_key,':',-1)))) AS bad_payment_truth,
  SUM(c.callback_event_id IS NULL) AS missing_callback,
  SUM(c.callback_event_id IS NOT NULL AND (c.callback_correlation_id<>a.callback_correlation_id
    OR c.requested_outcome<>'SUCCEEDED' OR c.result_state<>'APPLIED' OR c.sandbox_id IS NOT NULL
    OR c.support_session_id IS NOT NULL OR c.trace_id IS NOT NULL OR c.operation_id IS NOT NULL
    OR BINARY c.callback_idempotency_key<>BINARY CONCAT(@label,':c:',SUBSTRING_INDEX(i.idempotency_key,':',-1)))) AS bad_callback_truth,
  SUM(l.movement_id IS NULL) AS missing_payment_ledger,
  SUM(l.movement_id IS NOT NULL AND (l.business_event_key<>CONCAT('mock-payment:',a.attempt_id)
    OR BINARY l.product_id<>BINARY o.product_id OR l.inventory_delta<>0 OR l.activity_quota_delta<>0
    OR l.reservation_id IS NOT NULL OR l.activity_id IS NOT NULL OR l.sandbox_id IS NOT NULL
    OR l.payment_amount_minor<>o.total_price_minor OR BINARY l.payment_currency<>BINARY o.currency)) AS bad_ledger_truth,
  SUM(x.event_id IS NULL) AS missing_creation_outbox
FROM cohort o LEFT JOIN order_idempotency i ON i.order_id=o.order_id
LEFT JOIN mock_payment_attempt a ON a.order_id=o.order_id AND a.order_kind='STANDARD'
LEFT JOIN mock_payment_callback c ON c.attempt_id=a.attempt_id
LEFT JOIN inventory_ledger l ON l.order_id=o.order_id AND l.movement_type='STANDARD_PAYMENT'
LEFT JOIN commerce_outbox x ON x.aggregate_id=o.order_id AND x.aggregate_type='STANDARD_ORDER'
  AND x.aggregate_version=1 AND x.event_type='STANDARD_ORDER_CREATED';

-- Also inspect label-key rows independently of SKU membership; a wrong product/owner must
-- be visible rather than disappearing from the fixture-cohort joins above.
WITH fixture AS (
  SELECT product_id FROM JSON_TABLE(@products_json, '$[*]' COLUMNS(product_id VARCHAR(64) PATH '$.productId')) AS source
)
SELECT COUNT(*) AS label_order_keys,SUM(o.order_id IS NULL) AS key_without_order,
  SUM(o.order_id IS NOT NULL AND f.product_id IS NULL) AS label_order_outside_fixture
FROM order_idempotency i LEFT JOIN standard_order o ON o.order_id=i.order_id
LEFT JOIN fixture f ON BINARY f.product_id=BINARY o.product_id
WHERE BINARY LEFT(i.idempotency_key,CHAR_LENGTH(CONCAT(@label,':o:')))=BINARY CONCAT(@label,':o:');
COMMIT;
