-- Traffic is a dated store observation, not a second ledger of paid orders or sales.
CREATE TABLE retail_store_traffic_daily (
  local_date DATE NOT NULL PRIMARY KEY,
  visits BIGINT UNSIGNED NOT NULL,
  observed_at TIMESTAMP(6) NOT NULL,
  source_ref VARCHAR(128) NOT NULL,
  fixture_version VARCHAR(64) NOT NULL,
  CONSTRAINT chk_retail_traffic_source CHECK (
    CHAR_LENGTH(source_ref) > 0 AND CHAR_LENGTH(fixture_version) > 0
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Category is the current catalog assignment; sale amounts remain in merchant_paid_orders.
CREATE SQL SECURITY DEFINER VIEW merchant_listing_facts AS
SELECT p.product_id, m.family_id, COALESCE(m.family_id, p.product_id) AS listing_id,
       p.name, p.currency, p.price_minor, p.stock_quantity,
       p.publication_state, p.available, p.publication_version,
       JSON_UNQUOTE(JSON_EXTRACT(COALESCE(f.content, m.content, JSON_OBJECT()), '$.category'))
         AS category,
       o.unit_cost_minor, o.low_stock_threshold, o.content_quality, o.missing_attributes,
       o.facts_version, o.observed_at, o.source_ref
FROM product p
LEFT JOIN retail_product_metadata m ON m.product_id = p.product_id
LEFT JOIN retail_product_family f ON f.family_id = m.family_id
LEFT JOIN retail_product_operations o ON o.product_id = p.product_id;

-- local_date is an Asia/Shanghai calendar date; callers use the same paid-time window.
CREATE SQL SECURITY DEFINER VIEW merchant_store_traffic_daily AS
SELECT local_date, visits, observed_at, source_ref, fixture_version
FROM retail_store_traffic_daily;

-- Budgets are plans. Attributed spend/revenue remain nullable, dated external observations.
CREATE SQL SECURITY DEFINER VIEW merchant_campaign_facts AS
SELECT campaign_id, name, objective, channel, currency, budget_minor,
       starts_at, ends_at, state, version,
       spend_minor, revenue_minor, observation_start, observation_end,
       observation_source_kind, observation_source_ref, observed_at, fixture_version
FROM retail_campaign;
