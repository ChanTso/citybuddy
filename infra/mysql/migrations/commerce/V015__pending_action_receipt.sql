CREATE TABLE pending_action (
  pending_action_id CHAR(36) NOT NULL PRIMARY KEY,
  action_idempotency_key CHAR(64) NOT NULL,
  action_type ENUM('REFUND_REQUEST') NOT NULL,
  argument_hash CHAR(64) NOT NULL,
  user_subject VARCHAR(128) NOT NULL,
  support_session_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64) NOT NULL,
  turn_id CHAR(36) NOT NULL,
  required_scope VARCHAR(64) NOT NULL,
  sandbox_id VARCHAR(64) NULL,
  order_id CHAR(36) NOT NULL,
  order_kind ENUM('STANDARD', 'SECKILL') NOT NULL,
  payment_attempt_id CHAR(36) NOT NULL,
  target_order_version BIGINT UNSIGNED NOT NULL,
  amount_minor BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) NOT NULL,
  state ENUM('PREPARED', 'CONSUMED') NOT NULL DEFAULT 'PREPARED',
  state_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  expires_at TIMESTAMP(6) NOT NULL,
  consumed_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_pending_action_idempotency UNIQUE (action_idempotency_key),
  CONSTRAINT uq_pending_action_turn UNIQUE (user_subject, support_session_id, turn_id),
  CONSTRAINT chk_pending_action_state CHECK (
    (state = 'PREPARED' AND state_version = 1 AND consumed_at IS NULL)
    OR (state = 'CONSUMED' AND state_version = 2 AND consumed_at IS NOT NULL)
  ),
  CONSTRAINT chk_pending_action_amount CHECK (amount_minor > 0),
  CONSTRAINT chk_pending_action_expiry CHECK (expires_at > created_at),
  INDEX idx_pending_action_owner_created
    (user_subject, created_at, pending_action_id),
  INDEX idx_pending_action_target
    (order_id, payment_attempt_id, created_at, pending_action_id)
) ENGINE=InnoDB;

CREATE TABLE action_receipt (
  receipt_id CHAR(36) NOT NULL PRIMARY KEY,
  receipt_idempotency_key CHAR(64) NOT NULL,
  pending_action_id CHAR(36) NOT NULL,
  action_type ENUM('REFUND_REQUEST') NOT NULL,
  argument_hash CHAR(64) NOT NULL,
  result_hash CHAR(64) NOT NULL,
  user_subject VARCHAR(128) NOT NULL,
  support_session_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64) NOT NULL,
  turn_id CHAR(36) NOT NULL,
  sandbox_id VARCHAR(64) NULL,
  order_id CHAR(36) NOT NULL,
  payment_attempt_id CHAR(36) NOT NULL,
  refund_id CHAR(36) NOT NULL,
  resulting_resource_version BIGINT UNSIGNED NOT NULL,
  result_state ENUM('REQUESTED') NOT NULL,
  amount_minor BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) NOT NULL,
  outbox_event_id CHAR(36) NOT NULL,
  outbox_created_at TIMESTAMP(6) NOT NULL,
  committed_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_action_receipt_idempotency UNIQUE (receipt_idempotency_key),
  CONSTRAINT uq_action_receipt_pending UNIQUE (pending_action_id),
  CONSTRAINT uq_action_receipt_refund UNIQUE (refund_id),
  CONSTRAINT chk_action_receipt_amount CHECK (amount_minor > 0),
  CONSTRAINT chk_action_receipt_version CHECK (resulting_resource_version = 1),
  INDEX idx_action_receipt_owner_committed
    (user_subject, committed_at, receipt_id)
) ENGINE=InnoDB;
