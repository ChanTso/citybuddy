"""CB-155 single-use formal seckill measurement runner."""

from __future__ import annotations

PROFILE_ID = "cb155-formal-v1"
JMETER_VERSION = "5.6.3"
JMETER_SHA512 = (
    "5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e"
    "522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083"
)

SQL_BLOCKS = (
    (
        "Q01",
        """-- Q01, captured before warm-up.
SELECT a.activity_id, a.product_id, a.state, a.allocated_quota,
       a.projection_version, p.stock_quantity
FROM seckill_activity AS a
JOIN product AS p ON p.product_id = a.product_id
WHERE a.activity_id = :activityId AND p.product_id = :productId;""",
    ),
    (
        "Q02",
        """-- Q02.
SELECT COUNT(*) AS total_reservations,
       COUNT(DISTINCT reservation_id) AS distinct_reservations,
       COUNT(DISTINCT user_subject, activity_id) AS distinct_user_activity,
       (SELECT COUNT(*) FROM (
          SELECT user_subject, activity_id, idempotency_key
          FROM seckill_reservation WHERE activity_id = :activityId
          GROUP BY user_subject, activity_id, idempotency_key HAVING COUNT(*) > 1
        ) AS duplicate_keys) AS duplicate_idempotency_groups
FROM seckill_reservation WHERE activity_id = :activityId;""",
    ),
    (
        "Q03",
        """-- Q03, executed only after the runner has waited until :settleCutoff.
SELECT SUM(state = 'PENDING') AS pending_count,
       SUM(state = 'ADMITTED') AS admitted_count,
       SUM(state = 'REJECTED') AS rejected_count,
       SUM(state = 'ORDERED') AS ordered_count,
       SUM(state = 'CANCELLED') AS cancelled_count,
       SUM(state NOT IN ('PENDING','ADMITTED','REJECTED','ORDERED','CANCELLED')) AS unknown_state,
       SUM(state IN ('PENDING','ADMITTED')
           AND transaction_resolution_due_at <= :settleCutoff) AS overdue_nonterminal
FROM seckill_reservation WHERE activity_id = :activityId;""",
    ),
    (
        "Q04",
        """-- Q04.
SELECT
  (SELECT COUNT(*) FROM seckill_reservation
   WHERE activity_id = :activityId AND state IN ('ORDERED','CANCELLED'))
     AS successful_reservations,
  (SELECT COUNT(*) FROM seckill_order WHERE activity_id = :activityId) AS orders_for_activity,
  (SELECT COUNT(*) FROM seckill_reservation r
   LEFT JOIN seckill_order o ON o.reservation_id = r.reservation_id
   WHERE r.activity_id = :activityId AND r.state IN ('ORDERED','CANCELLED')
     AND o.order_id IS NULL) AS missing_orders,
  (SELECT COUNT(*) FROM seckill_order o
   LEFT JOIN seckill_reservation r ON r.reservation_id = o.reservation_id
   WHERE o.activity_id = :activityId AND r.reservation_id IS NULL) AS orphan_orders,
  (SELECT COUNT(*) FROM (
     SELECT reservation_id FROM seckill_order WHERE activity_id = :activityId
     GROUP BY reservation_id HAVING COUNT(*) > 1
   ) AS duplicate_order_groups) AS duplicate_orders,
  (SELECT COUNT(*) FROM seckill_order o
   JOIN seckill_reservation r ON r.reservation_id = o.reservation_id
   WHERE o.activity_id = :activityId AND
     (r.state NOT IN ('ORDERED','CANCELLED') OR r.order_id <> o.order_id
      OR r.user_subject <> o.user_subject OR r.activity_id <> o.activity_id
      OR r.quantity <> o.quantity)) AS binding_mismatches;""",
    ),
    (
        "Q05",
        """-- Q05: the checker requires every returned scalar to be zero.
SELECT
  (SELECT COUNT(*) FROM seckill_order o WHERE o.activity_id = :activityId
   AND (SELECT COUNT(*) FROM inventory_ledger l WHERE l.order_id = o.order_id
        AND l.movement_type = 'SECKILL_ORDER_CREATE') <> 1) AS bad_create_count,
  (SELECT COUNT(*) FROM seckill_order o WHERE o.activity_id = :activityId
   AND o.status = 'CANCELLED'
   AND (SELECT COUNT(*) FROM inventory_ledger l WHERE l.order_id = o.order_id
        AND l.movement_type = 'SECKILL_UNPAID_CANCEL') <> 1) AS bad_cancel_count,
  (SELECT COUNT(*) FROM seckill_order o WHERE o.activity_id = :activityId
   AND o.status <> 'CANCELLED'
   AND (SELECT COUNT(*) FROM inventory_ledger l WHERE l.order_id = o.order_id
        AND l.movement_type = 'SECKILL_UNPAID_CANCEL') <> 0) AS unexpected_cancel_count,
  (SELECT COUNT(*) FROM inventory_ledger l
   LEFT JOIN seckill_order o ON o.order_id = l.order_id
   LEFT JOIN seckill_reservation r ON r.reservation_id = l.reservation_id
   WHERE (l.activity_id = :activityId OR o.activity_id = :activityId
          OR r.activity_id = :activityId)
     AND l.movement_type IN ('SECKILL_ORDER_CREATE','SECKILL_UNPAID_CANCEL')
     AND o.order_id IS NOT NULL
     AND ((l.movement_type = 'SECKILL_ORDER_CREATE' AND
           (l.inventory_delta <> -o.quantity OR l.activity_quota_delta <> -o.quantity))
       OR (l.movement_type = 'SECKILL_UNPAID_CANCEL' AND
           (l.inventory_delta <> o.quantity OR l.activity_quota_delta <> o.quantity))))
    AS bad_quantity_count,
  (SELECT COUNT(*) FROM inventory_ledger l
   LEFT JOIN seckill_order o ON o.order_id = l.order_id
   LEFT JOIN seckill_reservation r ON r.reservation_id = l.reservation_id
   WHERE (l.activity_id = :activityId OR o.activity_id = :activityId
          OR r.activity_id = :activityId)
     AND l.movement_type NOT IN ('SECKILL_ORDER_CREATE','SECKILL_UNPAID_CANCEL'))
    AS unexpected_movement_types,
  (SELECT COUNT(*) FROM inventory_ledger l
   LEFT JOIN seckill_order o ON o.order_id = l.order_id
   LEFT JOIN seckill_reservation r ON r.reservation_id = l.reservation_id
   WHERE (l.activity_id = :activityId OR o.activity_id = :activityId
          OR r.activity_id = :activityId)
     AND (o.order_id IS NULL OR r.reservation_id IS NULL)) AS orphan_movements,
  (SELECT COUNT(*) FROM inventory_ledger l
   LEFT JOIN seckill_order o ON o.order_id = l.order_id
   LEFT JOIN seckill_reservation r ON r.reservation_id = l.reservation_id
   LEFT JOIN seckill_activity a ON a.activity_id = o.activity_id
   WHERE (l.activity_id = :activityId OR o.activity_id = :activityId
          OR r.activity_id = :activityId)
     AND o.order_id IS NOT NULL AND r.reservation_id IS NOT NULL
     AND (l.reservation_id <> o.reservation_id OR l.activity_id <> o.activity_id
       OR l.product_id <> o.product_id OR r.reservation_id <> o.reservation_id
       OR r.activity_id <> o.activity_id OR NOT (r.order_id <=> o.order_id)
       OR r.user_subject <> o.user_subject OR r.quantity <> o.quantity
       OR a.activity_id IS NULL OR a.product_id <> o.product_id)) AS binding_mismatches;""",
    ),
    (
        "Q06",
        """-- Q06.
SELECT p.stock_quantity AS final_stock,
       :baselineProductStock + COALESCE(SUM(l.inventory_delta), 0) AS expected_final_stock,
       -COALESCE(SUM(l.activity_quota_delta), 0) AS net_consumed_quota,
       COALESCE((SELECT SUM(r.quantity) FROM seckill_reservation r
                 WHERE r.activity_id = :activityId
                   AND r.state IN ('ADMITTED','ORDERED')), 0) AS active_quantity,
       a.allocated_quota AS final_allocated_quota,
       :baselineAllocatedQuota AS baseline_allocated_quota
FROM seckill_activity a
JOIN product p ON p.product_id = a.product_id
LEFT JOIN inventory_ledger l ON l.activity_id = a.activity_id
WHERE a.activity_id = :activityId AND p.product_id = :productId
GROUP BY p.stock_quantity, a.allocated_quota;""",
    ),
    (
        "Q07a",
        """-- Q07a: executed once per runtime replay reservation before sanitizing its locators.
SELECT r.reservation_id, r.activity_id, r.quantity, r.activity_projection_version,
       r.state, r.decision_code, r.projection_version, r.order_id,
       (SELECT COUNT(*) FROM seckill_order o
        WHERE o.reservation_id = r.reservation_id) AS order_count,
       (SELECT MIN(o.order_id) FROM seckill_order o
        WHERE o.reservation_id = r.reservation_id) AS canonical_order_id,
       (SELECT COUNT(*) FROM inventory_ledger l
        WHERE l.order_id = r.order_id
          AND l.movement_type = 'SECKILL_ORDER_CREATE') AS create_movement_count,
       (SELECT COUNT(*) FROM inventory_ledger l
        WHERE l.order_id = r.order_id
          AND l.movement_type = 'SECKILL_UNPAID_CANCEL') AS cancel_movement_count,
       (SELECT COUNT(*) FROM inventory_ledger l
        LEFT JOIN seckill_order o ON o.order_id = l.order_id
        LEFT JOIN seckill_activity a ON a.activity_id = o.activity_id
        WHERE (l.reservation_id = r.reservation_id OR l.order_id = r.order_id)
          AND (o.order_id IS NULL OR l.reservation_id <> r.reservation_id
            OR l.order_id <> r.order_id OR l.activity_id <> r.activity_id
            OR o.reservation_id <> r.reservation_id OR o.activity_id <> r.activity_id
            OR o.user_subject <> r.user_subject OR o.quantity <> r.quantity
            OR l.product_id <> o.product_id OR a.activity_id IS NULL
            OR a.product_id <> o.product_id)) AS movement_linkage_mismatches
FROM seckill_reservation r
WHERE r.reservation_id = :replayReservationId AND r.activity_id = :activityId;""",
    ),
    (
        "Q07b",
        """-- Q07b durable global uniqueness; the public half is defined below.
SELECT
  (SELECT COUNT(*) FROM (
    SELECT user_subject, activity_id, idempotency_key
    FROM seckill_reservation WHERE activity_id = :activityId
    GROUP BY user_subject, activity_id, idempotency_key HAVING COUNT(*) > 1
  ) d) AS duplicate_reservation_keys,
  (SELECT COUNT(*) FROM (
    SELECT reservation_id FROM seckill_order WHERE activity_id = :activityId
    GROUP BY reservation_id HAVING COUNT(*) > 1
  ) d) AS duplicate_order_keys,
  (SELECT COUNT(*) FROM (
    SELECT order_id, movement_type FROM inventory_ledger
    WHERE activity_id = :activityId
      AND movement_type IN ('SECKILL_ORDER_CREATE','SECKILL_UNPAID_CANCEL')
    GROUP BY order_id, movement_type HAVING COUNT(*) > 1
  ) d) AS duplicate_ledger_keys;""",
    ),
    (
        "Q08",
        """-- Q08 canonical row selected before and after the public ownership controls.
SELECT reservation_id, user_subject, activity_id, idempotency_key, intent_hash, quantity,
       activity_projection_version, state, decision_code, projection_version, order_id,
       transaction_resolution_due_at
FROM seckill_reservation WHERE reservation_id = :ownershipReservationId;""",
    ),
    (
        "Q09",
        """-- Q09 durable work closure; each scalar must be zero.
SELECT
  (SELECT COUNT(*) FROM seckill_reservation
   WHERE activity_id = :activityId AND state IN ('PENDING','ADMITTED')
     AND transaction_resolution_due_at <= :observationAt) AS overdue_reservation_resolution,
  (SELECT COUNT(*) FROM seckill_order
   WHERE activity_id = :activityId AND status = 'UNPAID'
     AND unpaid_deadline <= :observationAt) AS overdue_unpaid_orders,
  (SELECT COUNT(*) FROM seckill_order
   WHERE activity_id = :activityId AND timeout_dispatch_state = 'PENDING'
     AND created_at <= :dispatchSettleCutoff) AS overdue_timeout_dispatch,
  (SELECT COUNT(*) FROM seckill_order
   WHERE activity_id = :activityId AND timeout_dispatch_state = 'FAILED')
     AS failed_timeout_dispatch;""",
    ),
)


def main() -> None:
    raise SystemExit("CB-155 runner implementation is in progress")


if __name__ == "__main__":
    main()
