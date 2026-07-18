CREATE TABLE auth_eval_test_principal (
  provisioning_id CHAR(36) NOT NULL,
  opaque_handle VARCHAR(64) NOT NULL,
  subject VARCHAR(190) NOT NULL,
  sandbox_id VARCHAR(64) NOT NULL,
  case_correlation VARCHAR(128) NOT NULL,
  test_user_label VARCHAR(128) NOT NULL,
  permissions VARCHAR(512) NOT NULL,
  provision_idempotency_key VARCHAR(128) NOT NULL,
  ttl_seconds INT UNSIGNED NOT NULL,
  state ENUM('PROVISIONED', 'REVOKED') NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  revoke_idempotency_key VARCHAR(128) NULL,
  revoked_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (provisioning_id),
  UNIQUE KEY uq_auth_eval_handle (opaque_handle),
  UNIQUE KEY uq_auth_eval_subject (subject),
  UNIQUE KEY uq_auth_eval_sandbox_case (sandbox_id, case_correlation),
  UNIQUE KEY uq_auth_eval_provision_key (provision_idempotency_key),
  CONSTRAINT chk_auth_eval_ttl CHECK (ttl_seconds BETWEEN 60 AND 3600),
  CONSTRAINT chk_auth_eval_expiry CHECK (expires_at > created_at),
  CONSTRAINT chk_auth_eval_revocation CHECK (
    (state = 'PROVISIONED' AND revoke_idempotency_key IS NULL AND revoked_at IS NULL)
    OR
    (state = 'REVOKED' AND revoke_idempotency_key IS NOT NULL AND revoked_at IS NOT NULL)
  )
) ENGINE=InnoDB;
