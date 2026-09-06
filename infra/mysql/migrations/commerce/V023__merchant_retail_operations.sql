ALTER TABLE merchant_price_draft
  ADD COLUMN kind ENUM('PRICE_UPDATE', 'LISTING_UPDATE', 'INVENTORY_ACTION')
    NOT NULL DEFAULT 'PRICE_UPDATE',
  ADD COLUMN payload JSON NULL,
  ADD CONSTRAINT chk_merchant_change_payload CHECK (
    (kind = 'PRICE_UPDATE' AND payload IS NULL)
    OR (kind <> 'PRICE_UPDATE' AND payload IS NOT NULL AND JSON_TYPE(payload) = 'OBJECT')
  );

-- Operating observations are not public product attributes or a second stock balance.
CREATE TABLE retail_product_operations (
  product_id VARCHAR(64) NOT NULL PRIMARY KEY,
  unit_cost_minor BIGINT UNSIGNED NULL,
  low_stock_threshold BIGINT UNSIGNED NOT NULL,
  content_quality ENUM('good', 'needs_work') NULL,
  missing_attributes JSON NOT NULL,
  facts_version BIGINT UNSIGNED NOT NULL,
  observed_at TIMESTAMP(6) NOT NULL,
  source_ref VARCHAR(128) NOT NULL,
  CONSTRAINT fk_retail_product_operations_product
    FOREIGN KEY (product_id) REFERENCES product (product_id),
  CONSTRAINT chk_retail_operations_version CHECK (facts_version > 0),
  CONSTRAINT chk_retail_operations_missing CHECK (JSON_TYPE(missing_attributes) = 'ARRAY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
