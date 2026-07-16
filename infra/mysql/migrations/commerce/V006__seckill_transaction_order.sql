ALTER TABLE seckill_reservation
  DROP CHECK chk_seckill_reservation_projection_version;

ALTER TABLE seckill_reservation
  MODIFY state ENUM('PENDING', 'ADMITTED', 'REJECTED', 'ORDERED') NOT NULL DEFAULT 'PENDING',
  MODIFY decision_code ENUM(
    'ADMITTED',
    'ACTIVITY_INACTIVE',
    'NOT_OPEN',
    'EXPIRED',
    'STALE_VERSION',
    'EXHAUSTED',
    'DUPLICATE_USER',
    'TRANSACTION_TIMEOUT'
  ) NULL,
  ADD COLUMN transaction_resolution_due_at TIMESTAMP(6) NOT NULL
    DEFAULT (TIMESTAMPADD(SECOND, 11, CURRENT_TIMESTAMP(6))) AFTER projection_version,
  ADD COLUMN order_id CHAR(36) NULL AFTER projection_version,
  ADD CONSTRAINT uq_seckill_reservation_order UNIQUE (order_id);

ALTER TABLE seckill_reservation
  MODIFY transaction_resolution_due_at TIMESTAMP(6) NOT NULL,
  ADD INDEX idx_seckill_reservation_resolution_due
    (state, transaction_resolution_due_at, reservation_id),
  ADD CONSTRAINT chk_seckill_reservation_projection_version CHECK (
    transaction_resolution_due_at IS NOT NULL
    AND (
    (state = 'PENDING' AND decision_code IS NULL AND projection_version = 1 AND order_id IS NULL)
    OR
    (state = 'ADMITTED' AND decision_code IS NOT NULL AND decision_code = 'ADMITTED'
      AND projection_version = 2 AND order_id IS NULL)
    OR
    (state = 'REJECTED' AND decision_code IS NOT NULL
      AND decision_code <> 'ADMITTED' AND projection_version = 2 AND order_id IS NULL)
    OR
    (state = 'ORDERED' AND decision_code IS NOT NULL AND decision_code = 'ADMITTED'
      AND projection_version = 3 AND order_id IS NOT NULL)
    )
  );

CREATE TABLE seckill_order (
  order_id CHAR(36) NOT NULL PRIMARY KEY,
  reservation_id CHAR(36) NOT NULL,
  transaction_event_id CHAR(36) NOT NULL,
  timeout_event_id CHAR(36) NOT NULL,
  user_subject VARCHAR(128) NOT NULL,
  activity_id VARCHAR(64) NOT NULL,
  product_id VARCHAR(64) NOT NULL,
  product_name VARCHAR(200) NOT NULL,
  unit_price_minor BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) NOT NULL,
  quantity INT UNSIGNED NOT NULL,
  total_price_minor BIGINT UNSIGNED NOT NULL,
  status ENUM('UNPAID') NOT NULL DEFAULT 'UNPAID',
  state_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  unpaid_deadline TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_seckill_order_reservation UNIQUE (reservation_id),
  CONSTRAINT uq_seckill_order_activity_user UNIQUE (activity_id, user_subject),
  CONSTRAINT uq_seckill_order_transaction_event UNIQUE (transaction_event_id),
  CONSTRAINT uq_seckill_order_timeout_event UNIQUE (timeout_event_id),
  CONSTRAINT chk_seckill_order_quantity CHECK (quantity > 0),
  CONSTRAINT chk_seckill_order_state_version CHECK (state_version = 1),
  INDEX idx_seckill_order_owner_created (user_subject, created_at, order_id),
  INDEX idx_seckill_order_unpaid_deadline (status, unpaid_deadline, order_id)
) ENGINE=InnoDB;

CREATE TABLE inventory_ledger (
  movement_id CHAR(36) NOT NULL PRIMARY KEY,
  business_event_key VARCHAR(128) NOT NULL,
  movement_type ENUM('SECKILL_ORDER_CREATE') NOT NULL,
  order_id CHAR(36) NOT NULL,
  reservation_id CHAR(36) NOT NULL,
  activity_id VARCHAR(64) NOT NULL,
  product_id VARCHAR(64) NOT NULL,
  inventory_delta BIGINT NOT NULL,
  activity_quota_delta BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_inventory_ledger_business_event UNIQUE (business_event_key),
  CONSTRAINT uq_inventory_ledger_order_create UNIQUE (order_id, movement_type),
  CONSTRAINT chk_inventory_ledger_order_create CHECK (
    movement_type <> 'SECKILL_ORDER_CREATE'
    OR (inventory_delta < 0 AND activity_quota_delta = inventory_delta)
  ),
  INDEX idx_inventory_ledger_reservation (reservation_id, movement_id),
  INDEX idx_inventory_ledger_activity (activity_id, movement_id)
) ENGINE=InnoDB;
