-- Account and request identities are exact; SKU foreign keys retain the catalog's collation.
CREATE TABLE shopping_cart (
  user_subject VARCHAR(128) NOT NULL PRIMARY KEY,
  cart_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE shopping_cart_item (
  user_subject VARCHAR(128) NOT NULL,
  product_id VARCHAR(64) COLLATE utf8mb4_0900_ai_ci NOT NULL,
  quantity INT UNSIGNED NOT NULL,
  PRIMARY KEY (user_subject, product_id),
  CONSTRAINT fk_shopping_cart_item_owner FOREIGN KEY (user_subject)
    REFERENCES shopping_cart (user_subject),
  CONSTRAINT fk_shopping_cart_item_product FOREIGN KEY (product_id) REFERENCES product (product_id),
  CONSTRAINT chk_shopping_cart_item_quantity CHECK (quantity BETWEEN 1 AND 24)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE shopping_cart_command (
  user_subject VARCHAR(128) NOT NULL,
  command_key VARCHAR(128) NOT NULL,
  intent_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  operation ENUM('ADD', 'SET', 'REMOVE') NOT NULL,
  product_id VARCHAR(64) COLLATE utf8mb4_0900_ai_ci NOT NULL,
  before_quantity INT UNSIGNED NOT NULL,
  after_quantity INT UNSIGNED NOT NULL,
  applied_cart_version BIGINT UNSIGNED NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (user_subject, command_key),
  CONSTRAINT fk_shopping_cart_command_owner FOREIGN KEY (user_subject)
    REFERENCES shopping_cart (user_subject),
  -- Missing-item SET/REMOVE receipts intentionally do not require a product foreign key.
  CONSTRAINT chk_shopping_cart_command_quantities CHECK (
    before_quantity BETWEEN 0 AND 24 AND after_quantity BETWEEN 0 AND 24
    AND (operation <> 'ADD' OR after_quantity > before_quantity)
    AND (operation <> 'REMOVE' OR after_quantity = 0)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE shopping_checkout (
  checkout_id CHAR(36) NOT NULL PRIMARY KEY,
  user_subject VARCHAR(128) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  intent_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_cart_version BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) NOT NULL,
  total_minor BIGINT UNSIGNED NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_shopping_checkout_owner_key UNIQUE (user_subject, request_key),
  CONSTRAINT fk_shopping_checkout_owner FOREIGN KEY (user_subject)
    REFERENCES shopping_cart (user_subject),
  INDEX idx_shopping_checkout_owner_created (user_subject, created_at, checkout_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE shopping_checkout_order (
  checkout_id CHAR(36) NOT NULL,
  line_no INT UNSIGNED NOT NULL,
  order_id CHAR(36) COLLATE utf8mb4_0900_ai_ci NOT NULL,
  PRIMARY KEY (checkout_id, line_no),
  CONSTRAINT uq_shopping_checkout_order UNIQUE (order_id),
  CONSTRAINT fk_shopping_checkout_order_header FOREIGN KEY (checkout_id)
    REFERENCES shopping_checkout (checkout_id),
  CONSTRAINT fk_shopping_checkout_order_order FOREIGN KEY (order_id)
    REFERENCES standard_order (order_id),
  CONSTRAINT chk_shopping_checkout_order_line CHECK (line_no BETWEEN 1 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
