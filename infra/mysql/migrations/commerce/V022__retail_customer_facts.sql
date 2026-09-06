ALTER TABLE crm_profile
  MODIFY user_subject VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  ADD COLUMN loyalty_tier ENUM('NONE', 'MEMBER') NOT NULL DEFAULT 'NONE',
  ADD COLUMN default_location VARCHAR(160) NULL,
  ADD COLUMN preferences JSON NOT NULL DEFAULT (JSON_OBJECT()),
  ADD CONSTRAINT chk_crm_preferences_object CHECK (JSON_TYPE(preferences) = 'OBJECT');

CREATE TABLE retail_fulfillment_config (
  config_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  config_version BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) NOT NULL,
  time_zone VARCHAR(64) NOT NULL,
  rules JSON NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT chk_retail_fulfillment_config_version CHECK (config_version > 0),
  CONSTRAINT chk_retail_fulfillment_rules CHECK (JSON_TYPE(rules) = 'OBJECT')
) ENGINE=InnoDB;

-- Fulfillment is an observed business fact, not a stage inferred from an ETA.
CREATE TABLE retail_order_fulfillment (
  order_id CHAR(36) NOT NULL PRIMARY KEY,
  method ENUM('STANDARD', 'EXPRESS', 'FREIGHT', 'PICKUP') NOT NULL,
  stage ENUM('PROCESSING', 'PACKED', 'SHIPPED', 'OUT_FOR_DELIVERY', 'DELIVERED') NOT NULL,
  promised_delivery_at TIMESTAMP(6) NULL,
  estimated_delivery_at TIMESTAMP(6) NULL,
  packed_at TIMESTAMP(6) NULL,
  shipped_at TIMESTAMP(6) NULL,
  delivered_at TIMESTAMP(6) NULL,
  delay_reason VARCHAR(500) NULL,
  source_kind ENUM('FIXTURE') NOT NULL,
  source_ref VARCHAR(128) NOT NULL,
  observed_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_retail_fulfillment_order FOREIGN KEY (order_id) REFERENCES standard_order (order_id),
  CONSTRAINT chk_retail_fulfillment_stage CHECK (
    (stage = 'PROCESSING' AND packed_at IS NULL AND shipped_at IS NULL AND delivered_at IS NULL)
    OR (stage = 'PACKED' AND packed_at IS NOT NULL AND shipped_at IS NULL AND delivered_at IS NULL)
    OR (stage IN ('SHIPPED', 'OUT_FOR_DELIVERY') AND shipped_at IS NOT NULL AND delivered_at IS NULL)
    OR (stage = 'DELIVERED' AND shipped_at IS NOT NULL AND delivered_at IS NOT NULL)
  ),
  CONSTRAINT chk_retail_fulfillment_times CHECK (
    (packed_at IS NULL OR packed_at <= observed_at)
    AND (shipped_at IS NULL OR shipped_at <= observed_at)
    AND (delivered_at IS NULL OR delivered_at <= observed_at)
    AND (packed_at IS NULL OR shipped_at IS NULL OR packed_at <= shipped_at)
    AND (shipped_at IS NULL OR delivered_at IS NULL OR shipped_at <= delivered_at)
  ),
  CONSTRAINT chk_retail_pickup_not_shipped CHECK (
    method <> 'PICKUP' OR stage IN ('PROCESSING', 'PACKED')
  )
) ENGINE=InnoDB;

CREATE TABLE retail_order_issue (
  issue_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL PRIMARY KEY,
  order_id CHAR(36) NOT NULL,
  kind ENUM('delayed', 'return_spike', 'buyer_message', 'damaged') NOT NULL,
  summary VARCHAR(1000) NOT NULL,
  buyer_message_excerpt VARCHAR(4000) NULL,
  opened_at TIMESTAMP(6) NOT NULL,
  resolved_at TIMESTAMP(6) NULL,
  window_start TIMESTAMP(6) NULL,
  window_end TIMESTAMP(6) NULL,
  source_kind ENUM('FIXTURE') NOT NULL,
  source_ref VARCHAR(128) NOT NULL,
  CONSTRAINT fk_retail_issue_order FOREIGN KEY (order_id) REFERENCES standard_order (order_id),
  CONSTRAINT chk_retail_issue_window CHECK (
    (kind = 'return_spike' AND window_start IS NOT NULL AND window_end IS NOT NULL
      AND window_start < window_end)
    OR (kind <> 'return_spike' AND window_start IS NULL AND window_end IS NULL)
  ),
  CONSTRAINT chk_retail_issue_resolution CHECK (resolved_at IS NULL OR resolved_at >= opened_at),
  INDEX idx_retail_open_issues (resolved_at, opened_at, issue_id)
) ENGINE=InnoDB;
