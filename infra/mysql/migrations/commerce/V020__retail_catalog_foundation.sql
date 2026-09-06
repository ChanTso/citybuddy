-- Families are display aggregates; only product rows carry orderable SKU truth.
CREATE TABLE retail_product_family (
  family_id VARCHAR(64) NOT NULL PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  description TEXT NOT NULL,
  content JSON NOT NULL,
  options JSON NOT NULL,
  metadata_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  display_order INT UNSIGNED NOT NULL DEFAULT 0,
  CONSTRAINT chk_retail_family_version CHECK (metadata_version > 0),
  CONSTRAINT chk_retail_family_content CHECK (
    JSON_TYPE(content) = 'OBJECT'
    AND JSON_LENGTH(JSON_REMOVE(content,
      '$.brand', '$.category', '$.imageUrl', '$.longDescription', '$.rating',
      '$.reviewCount', '$.labels', '$.attributes', '$.specs', '$.reviewHighlights')) = 0
  ),
  CONSTRAINT chk_retail_family_options CHECK (
    JSON_TYPE(options) = 'ARRAY' AND JSON_LENGTH(options) > 0
  ),
  INDEX idx_retail_family_display (display_order, family_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE retail_product_metadata (
  product_id VARCHAR(64) NOT NULL PRIMARY KEY,
  family_id VARCHAR(64) NULL,
  content JSON NOT NULL,
  option_values JSON NOT NULL,
  metadata_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  display_order INT UNSIGNED NOT NULL DEFAULT 0,
  CONSTRAINT fk_retail_metadata_product FOREIGN KEY (product_id) REFERENCES product (product_id),
  CONSTRAINT fk_retail_metadata_family FOREIGN KEY (family_id)
    REFERENCES retail_product_family (family_id),
  CONSTRAINT chk_retail_metadata_version CHECK (metadata_version > 0),
  CONSTRAINT chk_retail_metadata_content CHECK (
    JSON_TYPE(content) = 'OBJECT'
    AND JSON_LENGTH(JSON_REMOVE(content,
      '$.brand', '$.category', '$.imageUrl', '$.longDescription', '$.rating',
      '$.reviewCount', '$.labels', '$.attributes', '$.specs', '$.reviewHighlights')) = 0
  ),
  CONSTRAINT chk_retail_metadata_options CHECK (
    JSON_TYPE(option_values) = 'OBJECT'
    AND ((family_id IS NULL AND JSON_LENGTH(option_values) = 0)
      OR (family_id IS NOT NULL AND JSON_LENGTH(option_values) > 0))
  ),
  INDEX idx_retail_metadata_family_display (family_id, display_order, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
