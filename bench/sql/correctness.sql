SELECT 'Q01 no oversell at pre-cancellation snapshot: admitted reservation quantity must not exceed allocated quota' AS check_name;
SELECT a.allocated_quota,
       (SELECT COALESCE(SUM(r.quantity),0) FROM seckill_reservation r
         WHERE r.activity_id = a.activity_id AND r.state IN ('ADMITTED','ORDERED')) AS admitted_quantity,
       CASE WHEN (SELECT COALESCE(SUM(r.quantity),0) FROM seckill_reservation r
                   WHERE r.activity_id = a.activity_id AND r.state IN ('ADMITTED','ORDERED'))
                 <= a.allocated_quota THEN 'PASS' ELSE 'FAIL' END AS verdict
FROM seckill_activity a WHERE a.activity_id = '${ACTIVITY}';

SELECT 'Q02 no oversell at pre-cancellation snapshot: durable ordered quantity must not exceed allocated quota' AS check_name;
SELECT a.allocated_quota, COALESCE(SUM(o.quantity),0) AS ordered_quantity,
       CASE WHEN COALESCE(SUM(o.quantity),0) <= a.allocated_quota THEN 'PASS' ELSE 'FAIL' END AS verdict
FROM seckill_activity a LEFT JOIN seckill_order o ON o.activity_id = a.activity_id
WHERE a.activity_id = '${ACTIVITY}' GROUP BY a.allocated_quota;

SELECT 'Q03 one order per user per activity (duplicate-order check)' AS check_name;
SELECT COUNT(*) AS users_with_multiple_orders,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict
FROM (SELECT user_subject FROM seckill_order WHERE activity_id = '${ACTIVITY}'
      GROUP BY user_subject HAVING COUNT(*) > 1) d;

SELECT 'Q04 one order per reservation' AS check_name;
SELECT COUNT(*) AS reservations_with_multiple_orders,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict
FROM (SELECT reservation_id FROM seckill_order WHERE activity_id = '${ACTIVITY}'
      GROUP BY reservation_id HAVING COUNT(*) > 1) d;

SELECT 'Q05 every order traces to an admitted reservation of the same owner' AS check_name;
SELECT COUNT(*) AS orphan_or_mismatched_orders,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict
FROM seckill_order o
LEFT JOIN seckill_reservation r ON r.reservation_id = o.reservation_id
WHERE o.activity_id = '${ACTIVITY}'
  AND (r.reservation_id IS NULL OR r.user_subject <> o.user_subject
       OR r.decision_code <> 'ADMITTED' OR r.activity_id <> o.activity_id);

SELECT 'Q06 ledger movement count equals order count' AS check_name;
SELECT (SELECT COUNT(*) FROM seckill_order WHERE activity_id = '${ACTIVITY}') AS orders,
       (SELECT COUNT(*) FROM inventory_ledger WHERE activity_id = '${ACTIVITY}') AS movements,
       CASE WHEN (SELECT COUNT(*) FROM seckill_order WHERE activity_id = '${ACTIVITY}')
               = (SELECT COUNT(*) FROM inventory_ledger WHERE activity_id = '${ACTIVITY}')
            THEN 'PASS' ELSE 'FAIL' END AS verdict;

SELECT 'Q07 ledger conserves quantity: inventory delta equals negated ordered quantity' AS check_name;
SELECT COALESCE(SUM(l.inventory_delta),0) AS inventory_delta,
       COALESCE(SUM(l.activity_quota_delta),0) AS quota_delta,
       (SELECT COALESCE(SUM(quantity),0) FROM seckill_order WHERE activity_id = '${ACTIVITY}') AS ordered_qty,
       CASE WHEN COALESCE(SUM(l.inventory_delta),0)
                 = -(SELECT COALESCE(SUM(quantity),0) FROM seckill_order WHERE activity_id = '${ACTIVITY}')
             AND COALESCE(SUM(l.inventory_delta),0) = COALESCE(SUM(l.activity_quota_delta),0)
            THEN 'PASS' ELSE 'FAIL' END AS verdict
FROM inventory_ledger l WHERE l.activity_id = '${ACTIVITY}';

SELECT 'Q08 product stock decremented by exactly the ordered quantity' AS check_name;
SELECT ${STOCK_BEFORE} AS stock_before, p.stock_quantity AS stock_after,
       ${STOCK_BEFORE} - p.stock_quantity AS decremented,
       (SELECT COALESCE(SUM(quantity),0) FROM seckill_order WHERE activity_id = '${ACTIVITY}') AS ordered_qty,
       CASE WHEN ${STOCK_BEFORE} - p.stock_quantity
                 = (SELECT COALESCE(SUM(quantity),0) FROM seckill_order WHERE activity_id = '${ACTIVITY}')
            THEN 'PASS' ELSE 'FAIL' END AS verdict
FROM product p WHERE p.product_id = '${PRODUCT}';

SELECT 'Q09 reservation state machine is closed and consistent with decision codes' AS check_name;
SELECT state, decision_code, COUNT(*) AS rows_found,
       CASE WHEN (state='ADMITTED' AND decision_code='ADMITTED')
              OR (state='ORDERED'  AND decision_code='ADMITTED')
              OR (state='REJECTED' AND decision_code <> 'ADMITTED')
              OR (state='CANCELLED')
            THEN 'PASS' ELSE 'FAIL' END AS verdict
FROM seckill_reservation WHERE activity_id = '${ACTIVITY}' GROUP BY state, decision_code;

SELECT 'Q10 no admitted reservation was left without a durable outcome' AS check_name;
SELECT COUNT(*) AS admitted_without_order,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict
FROM seckill_reservation r
WHERE r.activity_id = '${ACTIVITY}' AND r.decision_code = 'ADMITTED'
  AND NOT EXISTS (SELECT 1 FROM seckill_order o WHERE o.reservation_id = r.reservation_id);
