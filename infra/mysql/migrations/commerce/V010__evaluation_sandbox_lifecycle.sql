CREATE TABLE eval_sandbox (
  sandbox_id VARCHAR(64) NOT NULL,
  case_correlation VARCHAR(128) NOT NULL,
  reset_idempotency_key VARCHAR(128) NOT NULL,
  fixture_digest CHAR(64) NOT NULL,
  fixture_count SMALLINT UNSIGNED NOT NULL,
  test_user_label VARCHAR(128) NOT NULL,
  requested_ttl_seconds INT UNSIGNED NOT NULL,
  auth_provision_idempotency_key VARCHAR(128) NOT NULL,
  auth_revoke_idempotency_key VARCHAR(128) NOT NULL,
  opaque_handle CHAR(43) NULL,
  lifecycle_state ENUM('PROVISIONING', 'ACTIVE', 'DEAD') NOT NULL,
  auth_invalidation_state
    ENUM(
      'UNPROVISIONED',
      'PROVISIONED',
      'REVOKED',
      'EXPIRY_PROVEN'
    ) NOT NULL,
  death_reason ENUM('RESET_FAILED', 'COMPLETED', 'EXPIRED', 'ABANDONED') NULL,
  completion_idempotency_key VARCHAR(128) NULL,
  cleanup_attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
  cleanup_due_at TIMESTAMP(6) NULL,
  provisioning_due_at TIMESTAMP(6) NOT NULL,
  auth_expiry_upper_bound TIMESTAMP(6) NOT NULL,
  expires_at TIMESTAMP(6) NULL,
  activated_at TIMESTAMP(6) NULL,
  dead_at TIMESTAMP(6) NULL,
  closed_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (sandbox_id),
  UNIQUE KEY uq_eval_sandbox_case (case_correlation),
  UNIQUE KEY uq_eval_sandbox_reset_key (reset_idempotency_key),
  UNIQUE KEY uq_eval_sandbox_provision_key (auth_provision_idempotency_key),
  UNIQUE KEY uq_eval_sandbox_revoke_key (auth_revoke_idempotency_key),
  UNIQUE KEY uq_eval_sandbox_handle (opaque_handle),
  UNIQUE KEY uq_eval_sandbox_completion_key (completion_idempotency_key),
  KEY ix_eval_sandbox_cleanup (cleanup_due_at, lifecycle_state, sandbox_id),
  CONSTRAINT chk_eval_sandbox_fixture_count CHECK (fixture_count BETWEEN 1 AND 16),
  CONSTRAINT chk_eval_sandbox_ttl CHECK (requested_ttl_seconds BETWEEN 60 AND 3600),
  CONSTRAINT chk_eval_sandbox_deadline CHECK (
    provisioning_due_at > created_at
    AND auth_expiry_upper_bound > provisioning_due_at
  ),
  CONSTRAINT chk_eval_sandbox_handle CHECK (
    (auth_invalidation_state = 'UNPROVISIONED' AND opaque_handle IS NULL)
    OR (auth_invalidation_state IN ('PROVISIONED', 'REVOKED') AND opaque_handle IS NOT NULL)
    OR auth_invalidation_state = 'EXPIRY_PROVEN'
  ),
  CONSTRAINT chk_eval_sandbox_active CHECK (
    lifecycle_state <> 'ACTIVE'
    OR (
      auth_invalidation_state = 'PROVISIONED'
      AND opaque_handle IS NOT NULL
      AND expires_at IS NOT NULL
      AND activated_at IS NOT NULL
      AND death_reason IS NULL
      AND dead_at IS NULL
      AND closed_at IS NULL
    )
  ),
  CONSTRAINT chk_eval_sandbox_dead CHECK (
    lifecycle_state <> 'DEAD'
    OR (death_reason IS NOT NULL AND dead_at IS NOT NULL)
  ),
  CONSTRAINT chk_eval_sandbox_closed CHECK (
    closed_at IS NULL
    OR (
      lifecycle_state = 'DEAD'
      AND auth_invalidation_state IN ('REVOKED', 'EXPIRY_PROVEN')
    )
  ),
  CONSTRAINT chk_eval_sandbox_cleanup_attempts CHECK (cleanup_attempts <= 5)
) ENGINE=InnoDB;

CREATE TABLE eval_sandbox_product_fixture (
  sandbox_id VARCHAR(64) NOT NULL,
  product_id VARCHAR(64) NOT NULL,
  name VARCHAR(200) NOT NULL,
  description VARCHAR(1000) NOT NULL,
  price_minor BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) NOT NULL,
  stock_quantity BIGINT UNSIGNED NOT NULL,
  available BOOLEAN NOT NULL,
  publication_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (sandbox_id, product_id),
  KEY ix_eval_fixture_product_lookup (product_id, sandbox_id),
  CONSTRAINT fk_eval_fixture_product_sandbox
    FOREIGN KEY (sandbox_id) REFERENCES eval_sandbox (sandbox_id),
  CONSTRAINT chk_eval_fixture_price CHECK (price_minor > 0),
  CONSTRAINT chk_eval_fixture_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
  CONSTRAINT chk_eval_fixture_stock CHECK (stock_quantity <= 1000000),
  CONSTRAINT chk_eval_fixture_version CHECK (publication_version = 1)
) ENGINE=InnoDB;

CREATE TABLE eval_sandbox_effect_stub (
  sandbox_id VARCHAR(64) NOT NULL,
  effect_type ENUM('SMS') NOT NULL,
  correlation_key VARCHAR(128) NOT NULL,
  outcome ENUM('SUPPRESSED') NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (sandbox_id, effect_type, correlation_key),
  CONSTRAINT fk_eval_effect_stub_sandbox
    FOREIGN KEY (sandbox_id) REFERENCES eval_sandbox (sandbox_id),
  CONSTRAINT chk_eval_effect_stub_outcome CHECK (outcome = 'SUPPRESSED')
) ENGINE=InnoDB;
