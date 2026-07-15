CREATE TABLE crm_profile (
  user_subject VARCHAR(128) NOT NULL PRIMARY KEY,
  display_name VARCHAR(160) NOT NULL,
  contact_email VARCHAR(320) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE product (
  product_id VARCHAR(64) NOT NULL PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  description TEXT NOT NULL,
  price_minor BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) NOT NULL,
  stock_quantity BIGINT UNSIGNED NOT NULL,
  available BOOLEAN NOT NULL,
  publication_state ENUM('DRAFT', 'PUBLISHED', 'UNPUBLISHED') NOT NULL,
  publication_version BIGINT UNSIGNED NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT chk_product_publication_version CHECK (publication_version > 0)
) ENGINE=InnoDB;

CREATE TABLE catalog_metadata (
  singleton_id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
  publication_generation BIGINT UNSIGNED NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT chk_catalog_singleton CHECK (singleton_id = 1)
) ENGINE=InnoDB;

CREATE TABLE commerce_outbox (
  event_id CHAR(36) NOT NULL PRIMARY KEY,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id VARCHAR(64) NOT NULL,
  aggregate_version BIGINT UNSIGNED NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  payload JSON NOT NULL,
  publication_state ENUM('PENDING', 'PUBLISHED') NOT NULL DEFAULT 'PENDING',
  publish_attempts INT UNSIGNED NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  published_at TIMESTAMP(6) NULL,
  CONSTRAINT uq_outbox_aggregate_event
    UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
) ENGINE=InnoDB;
