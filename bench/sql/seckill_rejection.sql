SET SESSION time_zone='+00:00';
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
SELECT @citybuddy_sha AS citybuddy_commit,@label AS benchmark_label,UTC_TIMESTAMP(6) AS observed_at;
START TRANSACTION READ ONLY;
SELECT COUNT(*) AS activities,SUM(allocated_quota) AS allocated_quota,MIN(allocated_quota) AS min_quota,
 MAX(allocated_quota) AS max_quota FROM seckill_activity
 WHERE BINARY LEFT(activity_id,CHAR_LENGTH(@activity_prefix))=BINARY @activity_prefix;
SELECT state,decision_code,COUNT(*) AS reservations,SUM(quantity) AS units FROM seckill_reservation
 WHERE BINARY LEFT(activity_id,CHAR_LENGTH(@activity_prefix))=BINARY @activity_prefix
 GROUP BY state,decision_code;
SELECT status,timeout_dispatch_state,COUNT(*) AS orders,SUM(quantity) AS units,
 MIN(unpaid_deadline) AS earliest_deadline,
 TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),MIN(unpaid_deadline)) AS deadline_seconds_remaining
 FROM seckill_order WHERE BINARY LEFT(activity_id,CHAR_LENGTH(@activity_prefix))=BINARY @activity_prefix
 GROUP BY status,timeout_dispatch_state;
SELECT movement_type,COUNT(*) AS ledger_rows,SUM(inventory_delta) AS inventory_delta,
 SUM(activity_quota_delta) AS activity_quota_delta FROM inventory_ledger
 WHERE BINARY LEFT(activity_id,CHAR_LENGTH(@activity_prefix))=BINARY @activity_prefix GROUP BY movement_type;
SELECT p.product_id,p.stock_quantity,p.price_minor,p.publication_version,
 @stock_before-p.stock_quantity AS stock_consumed,
 (SELECT COALESCE(SUM(quantity),0) FROM seckill_order o WHERE BINARY o.product_id=BINARY p.product_id) AS ordered_units
 FROM product p WHERE BINARY product_id=BINARY @product_id;
SELECT COUNT(*) AS orphan_or_mismatched_orders FROM seckill_order o
 LEFT JOIN seckill_reservation r ON r.reservation_id=o.reservation_id
 WHERE BINARY LEFT(o.activity_id,CHAR_LENGTH(@activity_prefix))=BINARY @activity_prefix
 AND (r.reservation_id IS NULL OR BINARY r.user_subject<>BINARY o.user_subject
 OR BINARY r.activity_id<>BINARY o.activity_id OR r.state<>'ORDERED' OR r.decision_code<>'ADMITTED');
COMMIT;
