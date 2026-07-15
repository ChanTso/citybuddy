CREATE TABLE seckill_reservation (
  reservation_id CHAR(36) NOT NULL PRIMARY KEY,
  user_subject VARCHAR(128) NOT NULL,
  activity_id VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  intent_hash CHAR(64) NOT NULL,
  quantity INT UNSIGNED NOT NULL,
  activity_projection_version BIGINT UNSIGNED NOT NULL,
  state ENUM('PENDING', 'ADMITTED', 'REJECTED') NOT NULL DEFAULT 'PENDING',
  decision_code ENUM(
    'ADMITTED',
    'ACTIVITY_INACTIVE',
    'NOT_OPEN',
    'EXPIRED',
    'STALE_VERSION',
    'EXHAUSTED',
    'DUPLICATE_USER'
  ) NULL,
  projection_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_seckill_reservation_idempotency
    UNIQUE (user_subject, activity_id, idempotency_key),
  CONSTRAINT chk_seckill_reservation_quantity CHECK (quantity > 0),
  CONSTRAINT chk_seckill_reservation_activity_version
    CHECK (activity_projection_version > 0
      AND activity_projection_version <= 99999999999999),
  CONSTRAINT chk_seckill_reservation_projection_version CHECK (
    (state = 'PENDING' AND decision_code IS NULL AND projection_version = 1)
    OR
    (state = 'ADMITTED' AND decision_code IS NOT NULL
      AND decision_code = 'ADMITTED' AND projection_version = 2)
    OR
    (state = 'REJECTED' AND decision_code IS NOT NULL
      AND decision_code <> 'ADMITTED' AND projection_version = 2)
  ),
  INDEX idx_seckill_reservation_owner (user_subject, reservation_id),
  INDEX idx_seckill_reservation_activity_state (activity_id, state, reservation_id)
) ENGINE=InnoDB;
