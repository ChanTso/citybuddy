ALTER TABLE seckill_reservation
  DROP CHECK chk_seckill_reservation_projection_version;

ALTER TABLE seckill_reservation
  MODIFY state ENUM(
    'PENDING', 'ADMITTED', 'REJECTED', 'ORDERED', 'CANCELLED', 'UNFULFILLED'
  ) NOT NULL DEFAULT 'PENDING',
  ADD CONSTRAINT chk_seckill_reservation_projection_version CHECK (
    transaction_resolution_due_at IS NOT NULL
    AND (
      (state = 'PENDING' AND decision_code IS NULL
        AND projection_version = 1 AND order_id IS NULL)
      OR
      (state = 'ADMITTED' AND decision_code IS NOT NULL AND decision_code = 'ADMITTED'
        AND projection_version = 2 AND order_id IS NULL)
      OR
      (state = 'REJECTED' AND decision_code IS NOT NULL
        AND decision_code <> 'ADMITTED' AND projection_version = 2 AND order_id IS NULL)
      OR
      (state = 'ORDERED' AND decision_code IS NOT NULL AND decision_code = 'ADMITTED'
        AND projection_version = 3 AND order_id IS NOT NULL)
      OR
      (state = 'UNFULFILLED' AND decision_code IS NOT NULL AND decision_code = 'ADMITTED'
        AND projection_version = 3 AND order_id IS NULL)
      OR
      (state = 'CANCELLED' AND decision_code IS NOT NULL AND decision_code = 'ADMITTED'
        AND projection_version = 4 AND order_id IS NOT NULL)
    )
  );
