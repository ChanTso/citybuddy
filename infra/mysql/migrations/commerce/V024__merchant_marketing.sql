ALTER TABLE merchant_price_draft
  MODIFY COLUMN kind ENUM('PRICE_UPDATE', 'LISTING_UPDATE', 'INVENTORY_ACTION', 'PROMOTION', 'CAMPAIGN')
    NOT NULL DEFAULT 'PRICE_UPDATE';

CREATE TABLE retail_promotion (
  promotion_id CHAR(36) NOT NULL PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  currency CHAR(3) NOT NULL,
  discount_basis_points INT UNSIGNED NOT NULL,
  starts_at DATETIME(6) NOT NULL,
  ends_at DATETIME(6) NOT NULL,
  state ENUM('APPLIED') NOT NULL,
  version BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  applied_at DATETIME(6) NOT NULL,
  source_change_id CHAR(36) NOT NULL UNIQUE,
  CONSTRAINT fk_retail_promotion_change FOREIGN KEY (source_change_id) REFERENCES merchant_price_draft(draft_id),
  CONSTRAINT chk_retail_promotion_discount CHECK (discount_basis_points BETWEEN 1 AND 5000),
  CONSTRAINT chk_retail_promotion_window CHECK (starts_at < ends_at AND applied_at >= starts_at AND applied_at < ends_at),
  CONSTRAINT chk_retail_promotion_version CHECK (version > 0),
  INDEX idx_retail_promotion_created (created_at, promotion_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE retail_promotion_item (
  promotion_id CHAR(36) NOT NULL,
  product_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  approved_base_price_minor BIGINT UNSIGNED NOT NULL,
  promotion_price_minor BIGINT UNSIGNED NOT NULL,
  before_version BIGINT UNSIGNED NOT NULL,
  after_version BIGINT UNSIGNED NOT NULL,
  event_id CHAR(36) NOT NULL,
  PRIMARY KEY (promotion_id, product_id),
  CONSTRAINT fk_retail_promotion_item_promotion FOREIGN KEY (promotion_id) REFERENCES retail_promotion(promotion_id),
  CONSTRAINT fk_retail_promotion_item_product FOREIGN KEY (product_id) REFERENCES product(product_id),
  CONSTRAINT chk_retail_promotion_item_price CHECK (promotion_price_minor > 0 AND promotion_price_minor < approved_base_price_minor),
  CONSTRAINT chk_retail_promotion_item_versions CHECK (before_version > 0 AND after_version > before_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE retail_campaign (
  campaign_id VARCHAR(64) NOT NULL PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  objective VARCHAR(200) NULL,
  audience VARCHAR(300) NULL,
  copy_text VARCHAR(600) NULL,
  channel VARCHAR(40) NULL,
  currency CHAR(3) NOT NULL,
  budget_minor BIGINT UNSIGNED NULL,
  starts_at DATETIME(6) NULL,
  ends_at DATETIME(6) NULL,
  state ENUM('draft', 'active', 'paused', 'ended') NOT NULL,
  version BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  source_change_id CHAR(36) NULL,
  spend_minor BIGINT UNSIGNED NULL,
  revenue_minor BIGINT UNSIGNED NULL,
  observation_source_kind VARCHAR(32) NULL,
  observation_source_ref VARCHAR(128) NULL,
  observed_at DATETIME(6) NULL,
  observation_start DATETIME(6) NULL,
  observation_end DATETIME(6) NULL,
  fixture_version VARCHAR(64) NULL,
  CONSTRAINT fk_retail_campaign_change FOREIGN KEY (source_change_id) REFERENCES merchant_price_draft(draft_id),
  CONSTRAINT chk_retail_campaign_budget CHECK (budget_minor IS NULL OR budget_minor <= 1000000),
  CONSTRAINT chk_retail_campaign_version CHECK (version > 0),
  CONSTRAINT chk_retail_campaign_window CHECK (starts_at IS NULL OR ends_at IS NULL OR starts_at < ends_at),
  CONSTRAINT chk_retail_campaign_observation_window CHECK (
    (observation_start IS NULL AND observation_end IS NULL)
    OR (observation_start IS NOT NULL AND observation_end IS NOT NULL AND observation_start < observation_end)
  ),
  INDEX idx_retail_campaign_created (created_at, campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
