CREATE TABLE standard_order (
  order_id CHAR(36) NOT NULL PRIMARY KEY,
  user_subject VARCHAR(128) NOT NULL,
  product_id VARCHAR(64) NOT NULL,
  product_name VARCHAR(200) NOT NULL,
  unit_price_minor BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) NOT NULL,
  quantity INT UNSIGNED NOT NULL,
  total_price_minor BIGINT UNSIGNED NOT NULL,
  product_version BIGINT UNSIGNED NOT NULL,
  status ENUM('UNPAID') NOT NULL DEFAULT 'UNPAID',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT chk_standard_order_quantity CHECK (quantity > 0),
  CONSTRAINT chk_standard_order_product_version CHECK (product_version > 0),
  INDEX idx_standard_order_owner_created (user_subject, created_at, order_id)
) ENGINE=InnoDB;

CREATE TABLE order_idempotency (
  user_subject VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  intent_hash CHAR(64) NOT NULL,
  order_id CHAR(36) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (user_subject, idempotency_key),
  CONSTRAINT uq_order_idempotency_order UNIQUE (order_id)
) ENGINE=InnoDB;
