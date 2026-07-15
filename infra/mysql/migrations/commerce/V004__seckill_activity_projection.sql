CREATE TABLE seckill_activity (
  activity_id VARCHAR(64) NOT NULL PRIMARY KEY,
  product_id VARCHAR(64) NOT NULL,
  starts_at TIMESTAMP(6) NOT NULL,
  ends_at TIMESTAMP(6) NOT NULL,
  state ENUM('DRAFT', 'ACTIVE', 'CLOSED') NOT NULL,
  allocated_quota BIGINT UNSIGNED NOT NULL,
  projection_version BIGINT UNSIGNED NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT chk_seckill_activity_window CHECK (starts_at < ends_at),
  CONSTRAINT chk_seckill_activity_quota CHECK (allocated_quota > 0),
  CONSTRAINT chk_seckill_activity_projection_version CHECK (projection_version > 0),
  INDEX idx_seckill_activity_product_window (product_id, starts_at, ends_at)
) ENGINE=InnoDB;
