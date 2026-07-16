ALTER TABLE standard_order
  MODIFY status ENUM('UNPAID', 'PAID') NOT NULL DEFAULT 'UNPAID',
  ADD COLUMN state_version BIGINT UNSIGNED NOT NULL DEFAULT 1 AFTER status,
  ADD CONSTRAINT chk_standard_order_payment_state CHECK (
    (status = 'UNPAID' AND state_version = 1)
    OR (status = 'PAID' AND state_version = 2)
  );

CREATE TABLE mock_payment_attempt (
  attempt_id CHAR(36) NOT NULL PRIMARY KEY,
  callback_correlation_id CHAR(36) NOT NULL,
  user_subject VARCHAR(128) NOT NULL,
  order_id CHAR(36) NOT NULL,
  order_kind ENUM('STANDARD', 'SECKILL') NOT NULL,
  request_idempotency_key VARCHAR(128) NOT NULL,
  intent_hash CHAR(64) NOT NULL,
  amount_minor BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) NOT NULL,
  state ENUM('PENDING', 'SUCCEEDED', 'FAILED') NOT NULL DEFAULT 'PENDING',
  state_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  succeeded_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_mock_payment_callback_correlation UNIQUE (callback_correlation_id),
  CONSTRAINT uq_mock_payment_request UNIQUE (user_subject, request_idempotency_key),
  CONSTRAINT uq_mock_payment_order UNIQUE (order_kind, order_id),
  CONSTRAINT chk_mock_payment_amount CHECK (amount_minor > 0),
  CONSTRAINT chk_mock_payment_attempt_state CHECK (
    (state = 'PENDING' AND state_version = 1 AND succeeded_at IS NULL)
    OR (state = 'SUCCEEDED' AND state_version = 2 AND succeeded_at IS NOT NULL)
    OR (state = 'FAILED' AND state_version = 2 AND succeeded_at IS NULL)
  ),
  INDEX idx_mock_payment_owner_created (user_subject, created_at, attempt_id)
) ENGINE=InnoDB;

CREATE TABLE mock_payment_callback (
  callback_event_id CHAR(36) NOT NULL PRIMARY KEY,
  callback_idempotency_key VARCHAR(128) NOT NULL,
  attempt_id CHAR(36) NOT NULL,
  callback_correlation_id CHAR(36) NOT NULL,
  intent_hash CHAR(64) NOT NULL,
  requested_outcome ENUM('SUCCEEDED') NOT NULL,
  result_state ENUM('APPLIED') NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_mock_payment_callback_key UNIQUE (callback_idempotency_key),
  CONSTRAINT uq_mock_payment_callback_attempt UNIQUE (attempt_id),
  CONSTRAINT uq_mock_payment_callback_event_correlation
    UNIQUE (callback_event_id, callback_correlation_id),
  INDEX idx_mock_payment_callback_correlation (callback_correlation_id, created_at)
) ENGINE=InnoDB;

ALTER TABLE inventory_ledger
  DROP CHECK chk_inventory_ledger_seckill_movement;

ALTER TABLE inventory_ledger
  MODIFY movement_type ENUM(
    'SECKILL_ORDER_CREATE',
    'SECKILL_UNPAID_CANCEL',
    'STANDARD_PAYMENT',
    'SECKILL_PAYMENT'
  ) NOT NULL,
  MODIFY reservation_id CHAR(36) NULL,
  MODIFY activity_id VARCHAR(64) NULL,
  ADD COLUMN payment_amount_minor BIGINT UNSIGNED NULL AFTER activity_quota_delta,
  ADD COLUMN payment_currency CHAR(3) NULL AFTER payment_amount_minor,
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
    (movement_type = 'STANDARD_PAYMENT'
      AND reservation_id IS NULL AND activity_id IS NULL
      AND inventory_delta = 0 AND activity_quota_delta = 0
      AND payment_amount_minor IS NOT NULL AND payment_amount_minor > 0
      AND payment_currency IS NOT NULL)
    OR
    (movement_type = 'SECKILL_PAYMENT'
      AND reservation_id IS NOT NULL AND activity_id IS NOT NULL
      AND inventory_delta = 0 AND activity_quota_delta = 0
      AND payment_amount_minor IS NOT NULL AND payment_amount_minor > 0
      AND payment_currency IS NOT NULL)
  );
