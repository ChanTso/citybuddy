-- Caller supplies the measured @citybuddy_sha and exact @activity.
SET SESSION time_zone='+00:00';
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
SET TRANSACTION READ ONLY;
START TRANSACTION WITH CONSISTENT SNAPSHOT;
SELECT @citybuddy_sha AS citybuddy_commit, UTC_TIMESTAMP(6) AS observed_at_utc;
SELECT COUNT(*) AS mismatched_order_reservation_bindings FROM seckill_order o LEFT JOIN seckill_reservation r ON r.reservation_id=o.reservation_id WHERE o.activity_id=@activity AND (r.reservation_id IS NULL OR r.order_id<>o.order_id OR r.user_subject<>o.user_subject OR r.activity_id<>o.activity_id OR r.quantity<>o.quantity OR r.state<>'ORDERED' OR r.decision_code<>'ADMITTED');
SELECT COUNT(*) AS admitted_without_order FROM seckill_reservation r LEFT JOIN seckill_order o ON o.reservation_id=r.reservation_id WHERE r.activity_id=@activity AND r.decision_code='ADMITTED' AND o.order_id IS NULL;
SELECT COUNT(*) AS duplicate_order_users FROM (SELECT user_subject FROM seckill_order WHERE activity_id=@activity GROUP BY user_subject HAVING COUNT(*)<>1) d;
SELECT COUNT(*) AS duplicate_order_reservations FROM (SELECT reservation_id FROM seckill_order WHERE activity_id=@activity GROUP BY reservation_id HAVING COUNT(*)<>1) d;
SELECT COUNT(*) AS bad_order_create_ledger FROM seckill_order o LEFT JOIN inventory_ledger l ON l.order_id=o.order_id AND l.movement_type='SECKILL_ORDER_CREATE' WHERE o.activity_id=@activity AND (l.movement_id IS NULL OR l.reservation_id<>o.reservation_id OR l.activity_id<>o.activity_id OR l.product_id<>o.product_id OR l.inventory_delta<>-CAST(o.quantity AS SIGNED) OR l.activity_quota_delta<>-CAST(o.quantity AS SIGNED));
SELECT COUNT(*) AS unexpected_ledger_movements FROM inventory_ledger WHERE activity_id=@activity AND movement_type<>'SECKILL_ORDER_CREATE';
SELECT COUNT(*) AS orders_created_at_or_after_unpaid_deadline FROM seckill_order WHERE activity_id=@activity AND created_at>=unpaid_deadline;
WITH waits AS (
 SELECT TIMESTAMPDIFF(MICROSECOND,r.created_at,o.created_at)/1000.0 AS wait_ms
 FROM seckill_order o JOIN seckill_reservation r ON r.reservation_id=o.reservation_id WHERE o.activity_id=@activity
), ranked AS (
 SELECT wait_ms, ROW_NUMBER() OVER (ORDER BY wait_ms) AS rn,COUNT(*) OVER () AS n FROM waits
)
SELECT COUNT(*) AS orders,MIN(wait_ms) AS min_wait_ms,
 MAX(CASE WHEN rn=CEIL(n*0.50) THEN wait_ms END) AS p50_nearest_rank_ms,
 MAX(CASE WHEN rn=CEIL(n*0.95) THEN wait_ms END) AS p95_nearest_rank_ms,
 MAX(CASE WHEN rn=CEIL(n*0.99) THEN wait_ms END) AS p99_nearest_rank_ms,
 MAX(wait_ms) AS max_wait_ms, SUM(wait_ms<0) AS negative_waits FROM ranked;
WITH bounds AS (
 SELECT MIN(r.created_at) AS first_reservation,MIN(o.created_at) AS first_order,MAX(o.created_at) AS last_order,COUNT(*) AS orders
 FROM seckill_order o JOIN seckill_reservation r ON r.reservation_id=o.reservation_id WHERE o.activity_id=@activity
)
SELECT first_reservation,first_order,last_order,orders,
 TIMESTAMPDIFF(MICROSECOND,first_reservation,last_order)/1000000.0 AS batch_processing_seconds,
 orders/(TIMESTAMPDIFF(MICROSECOND,first_reservation,last_order)/1000000.0) AS batch_orders_per_second
FROM bounds;
WITH origin AS (
 SELECT CAST(DATE_FORMAT(MIN(created_at),'%Y-%m-%d %H:%i:%s') AS DATETIME(6)) AS t0
 FROM seckill_reservation WHERE activity_id=@activity AND decision_code='ADMITTED'
), binned AS (
 SELECT t0,FLOOR(TIMESTAMPDIFF(MICROSECOND,t0,o.created_at)/60000000) AS bin
 FROM seckill_order o CROSS JOIN origin WHERE o.activity_id=@activity
)
SELECT TIMESTAMPADD(SECOND,bin*60,t0) AS window_start_utc,TIMESTAMPADD(SECOND,(bin+1)*60,t0) AS window_end_utc,
 COUNT(*) AS orders,60 AS denominator_seconds,COUNT(*)/60.0 AS orders_per_second
FROM binned GROUP BY t0,bin ORDER BY bin;
SELECT p.stock_quantity, a.allocated_quota,
 (SELECT COALESCE(SUM(quantity),0) FROM seckill_order WHERE activity_id=a.activity_id) AS ordered_units,
 p.stock_quantity+(SELECT COALESCE(SUM(quantity),0) FROM seckill_order WHERE activity_id=a.activity_id) AS reconstructed_initial_stock,
 (SELECT COALESCE(SUM(-inventory_delta),0) FROM inventory_ledger WHERE activity_id=a.activity_id) AS ledger_stock_used,
 (SELECT COALESCE(SUM(-activity_quota_delta),0) FROM inventory_ledger WHERE activity_id=a.activity_id) AS ledger_quota_used
FROM product p JOIN seckill_activity a ON a.product_id=p.product_id WHERE a.activity_id=@activity;
WITH origin AS (SELECT MIN(created_at) AS t0 FROM seckill_reservation WHERE activity_id=@activity AND decision_code='ADMITTED'),
waits AS (SELECT FLOOR(TIMESTAMPDIFF(MICROSECOND,t0,r.created_at)/60000000) AS cohort,
 TIMESTAMPDIFF(MICROSECOND,r.created_at,o.created_at)/1000.0 AS wait_ms
 FROM seckill_reservation r JOIN seckill_order o ON o.reservation_id=r.reservation_id CROSS JOIN origin WHERE r.activity_id=@activity),
ranked AS (SELECT cohort,wait_ms,ROW_NUMBER() OVER(PARTITION BY cohort ORDER BY wait_ms) AS rn,COUNT(*) OVER(PARTITION BY cohort) AS n FROM waits)
SELECT cohort AS reservation_minute,COUNT(*) AS completed_orders,MIN(wait_ms) AS min_ms,
 MAX(CASE WHEN rn=CEIL(n*.5) THEN wait_ms END) AS p50_ms,
 MAX(CASE WHEN rn=CEIL(n*.99) THEN wait_ms END) AS p99_ms,MAX(wait_ms) AS max_ms
FROM ranked GROUP BY cohort ORDER BY cohort;
COMMIT;
