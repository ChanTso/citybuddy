ALTER TABLE mock_payment_attempt
  DROP CHECK chk_mock_payment_attempt_state;

ALTER TABLE mock_payment_attempt
  ADD COLUMN refunded_amount_minor BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER amount_minor,
  ADD CONSTRAINT chk_mock_payment_attempt_state CHECK (
    refunded_amount_minor <= amount_minor
    AND (
      (state = 'PENDING' AND state_version = 1 AND succeeded_at IS NULL
        AND refunded_amount_minor = 0)
      OR (state = 'SUCCEEDED' AND state_version = 2 AND succeeded_at IS NOT NULL)
      OR (state = 'FAILED' AND state_version = 2 AND succeeded_at IS NULL
        AND refunded_amount_minor = 0)
    )
  );

CREATE TABLE mock_refund (
  refund_id CHAR(36) NOT NULL PRIMARY KEY,
  user_subject VARCHAR(128) NOT NULL,
  order_id CHAR(36) NOT NULL,
  order_kind ENUM('STANDARD', 'SECKILL') NOT NULL,
  payment_attempt_id CHAR(36) NOT NULL,
  request_idempotency_key VARCHAR(128) NOT NULL,
  intent_hash CHAR(64) NOT NULL,
  eligible_amount_minor BIGINT UNSIGNED NOT NULL,
  requested_amount_minor BIGINT UNSIGNED NOT NULL,
  refunded_amount_minor BIGINT UNSIGNED NOT NULL DEFAULT 0,
  currency CHAR(3) NOT NULL,
  state ENUM('REQUESTED', 'PROCESSING', 'SUCCEEDED', 'FAILED')
    NOT NULL DEFAULT 'REQUESTED',
  state_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  failure_code VARCHAR(64) NULL,
  processing_at TIMESTAMP(6) NULL,
  completed_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_mock_refund_request UNIQUE (user_subject, order_id, request_idempotency_key),
  CONSTRAINT chk_mock_refund_amount CHECK (
    eligible_amount_minor > 0
    AND requested_amount_minor > 0
    AND requested_amount_minor <= eligible_amount_minor
    AND refunded_amount_minor <= requested_amount_minor
  ),
  CONSTRAINT chk_mock_refund_state CHECK (
    (state = 'REQUESTED' AND state_version = 1 AND refunded_amount_minor = 0
      AND failure_code IS NULL AND processing_at IS NULL AND completed_at IS NULL)
    OR (state = 'PROCESSING' AND state_version = 2 AND refunded_amount_minor = 0
      AND failure_code IS NULL AND processing_at IS NOT NULL AND completed_at IS NULL)
    OR (state = 'SUCCEEDED' AND state_version = 3
      AND refunded_amount_minor = requested_amount_minor
      AND failure_code IS NULL AND processing_at IS NOT NULL AND completed_at IS NOT NULL)
    OR (state = 'FAILED' AND state_version = 3 AND refunded_amount_minor = 0
      AND failure_code IS NOT NULL AND processing_at IS NOT NULL AND completed_at IS NOT NULL)
  ),
  INDEX idx_mock_refund_attempt_state
    (payment_attempt_id, state, created_at, refund_id),
  INDEX idx_mock_refund_owner_created
    (user_subject, created_at, refund_id)
) ENGINE=InnoDB;

ALTER TABLE inventory_ledger
  DROP CHECK chk_inventory_ledger_movement,
  DROP INDEX uq_inventory_ledger_order_create;

ALTER TABLE inventory_ledger
  MODIFY movement_type ENUM(
    'SECKILL_ORDER_CREATE',
    'SECKILL_UNPAID_CANCEL',
    'STANDARD_PAYMENT',
    'SECKILL_PAYMENT',
    'STANDARD_REFUND',
    'SECKILL_REFUND'
  ) NOT NULL,
  ADD COLUMN single_movement_type VARCHAR(32)
    GENERATED ALWAYS AS (
      CASE
        WHEN movement_type IN (
          'SECKILL_ORDER_CREATE',
          'SECKILL_UNPAID_CANCEL',
          'STANDARD_PAYMENT',
          'SECKILL_PAYMENT'
        ) THEN movement_type
        ELSE NULL
      END
    ) STORED AFTER movement_type,
  ADD CONSTRAINT uq_inventory_ledger_single_movement
    UNIQUE (order_id, single_movement_type),
  ADD CONSTRAINT chk_inventory_ledger_movement CHECK (
    (movement_type = 'SECKILL_ORDER_CREATE'
      AND reservation_id IS NOT NULL AND activity_id IS NOT NULL
      AND inventory_delta < 0 AND activity_quota_delta = inventory_delta
      AND payment_amount_minor IS NULL AND payment_currency IS NULL)
    OR
    (movement_type = 'SECKILL_UNPAID_CANCEL'
      AND reservation_id IS NOT NULL AND activity_id IS NOT NULL
      AND inventory_delta > 0 AND activity_quota_delta = inventory_delta
      AND payment_amount_minor IS NULL AND payment_currency IS NULL)
    OR
    (movement_type IN ('STANDARD_PAYMENT', 'STANDARD_REFUND')
      AND reservation_id IS NULL AND activity_id IS NULL
      AND inventory_delta = 0 AND activity_quota_delta = 0
      AND payment_amount_minor IS NOT NULL AND payment_amount_minor > 0
      AND payment_currency IS NOT NULL)
    OR
    (movement_type IN ('SECKILL_PAYMENT', 'SECKILL_REFUND')
      AND reservation_id IS NOT NULL AND activity_id IS NOT NULL
      AND inventory_delta = 0 AND activity_quota_delta = 0
      AND payment_amount_minor IS NOT NULL AND payment_amount_minor > 0
      AND payment_currency IS NOT NULL)
  );
