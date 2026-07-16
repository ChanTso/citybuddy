ALTER TABLE seckill_reservation
  DROP CHECK chk_seckill_reservation_projection_version;

ALTER TABLE seckill_reservation
  MODIFY state ENUM('PENDING', 'ADMITTED', 'REJECTED', 'ORDERED', 'CANCELLED')
    NOT NULL DEFAULT 'PENDING',
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
      (state = 'CANCELLED' AND decision_code IS NOT NULL AND decision_code = 'ADMITTED'
        AND projection_version = 4 AND order_id IS NOT NULL)
    )
  );

ALTER TABLE seckill_order
  DROP CHECK chk_seckill_order_state_version;

ALTER TABLE seckill_order
  MODIFY status ENUM('UNPAID', 'CANCELLED', 'PAID') NOT NULL DEFAULT 'UNPAID',
  ADD COLUMN timeout_dispatch_state ENUM('PENDING', 'SENT', 'FAILED')
    NOT NULL DEFAULT 'PENDING' AFTER unpaid_deadline,
  ADD COLUMN timeout_dispatch_attempts INT UNSIGNED NOT NULL DEFAULT 0
    AFTER timeout_dispatch_state,
  ADD COLUMN timeout_broker_message_id VARCHAR(128) NULL AFTER timeout_dispatch_attempts,
  ADD COLUMN timeout_dispatched_at TIMESTAMP(6) NULL AFTER timeout_broker_message_id,
  ADD COLUMN timeout_dispatch_error VARCHAR(500) NULL AFTER timeout_dispatched_at,
  ADD CONSTRAINT chk_seckill_order_state_version CHECK (
    (status = 'UNPAID' AND state_version = 1)
    OR (status = 'CANCELLED' AND state_version = 2)
    OR (status = 'PAID' AND state_version >= 2)
  ),
  ADD CONSTRAINT chk_seckill_order_timeout_dispatch CHECK (
    (timeout_dispatch_state = 'PENDING'
      AND timeout_broker_message_id IS NULL AND timeout_dispatched_at IS NULL)
    OR
    (timeout_dispatch_state = 'SENT'
      AND timeout_broker_message_id IS NOT NULL AND timeout_dispatched_at IS NOT NULL
      AND timeout_dispatch_error IS NULL)
    OR
    (timeout_dispatch_state = 'FAILED'
      AND timeout_dispatch_attempts > 0 AND timeout_broker_message_id IS NULL
      AND timeout_dispatched_at IS NULL AND timeout_dispatch_error IS NOT NULL)
  ),
  ADD INDEX idx_seckill_order_timeout_dispatch
    (timeout_dispatch_state, status, created_at, order_id);

ALTER TABLE inventory_ledger
  DROP CHECK chk_inventory_ledger_order_create;

ALTER TABLE inventory_ledger
  MODIFY movement_type ENUM('SECKILL_ORDER_CREATE', 'SECKILL_UNPAID_CANCEL') NOT NULL,
  ADD CONSTRAINT chk_inventory_ledger_seckill_movement CHECK (
    (movement_type = 'SECKILL_ORDER_CREATE'
      AND inventory_delta < 0 AND activity_quota_delta = inventory_delta)
    OR
    (movement_type = 'SECKILL_UNPAID_CANCEL'
      AND inventory_delta > 0 AND activity_quota_delta = inventory_delta)
  );
