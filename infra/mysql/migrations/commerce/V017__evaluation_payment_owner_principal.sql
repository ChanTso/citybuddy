ALTER TABLE eval_sandbox
  ADD COLUMN payment_owner_test_user_label VARCHAR(128) NULL,
  ADD COLUMN payment_owner_case_correlation VARCHAR(128) NULL,
  ADD COLUMN payment_owner_auth_provision_idempotency_key VARCHAR(128) NULL,
  ADD COLUMN payment_owner_auth_revoke_idempotency_key VARCHAR(128) NULL,
  ADD COLUMN payment_owner_opaque_handle CHAR(43) NULL,
  ADD COLUMN payment_owner_auth_invalidation_state
    ENUM(
      'UNPROVISIONED',
      'PROVISIONED',
      'REVOKED',
      'EXPIRY_PROVEN'
    ) NULL,
  ADD COLUMN payment_owner_auth_expiry_upper_bound TIMESTAMP(6) NULL,
  ADD COLUMN payment_owner_expires_at TIMESTAMP(6) NULL,
  ADD UNIQUE KEY uq_eval_sandbox_payment_owner_provision_key
    (payment_owner_auth_provision_idempotency_key),
  ADD UNIQUE KEY uq_eval_sandbox_payment_owner_revoke_key
    (payment_owner_auth_revoke_idempotency_key),
  ADD UNIQUE KEY uq_eval_sandbox_payment_owner_handle
    (payment_owner_opaque_handle),
  ADD CONSTRAINT chk_eval_sandbox_payment_owner CHECK (
    (
      payment_owner_test_user_label IS NULL
      AND payment_owner_case_correlation IS NULL
      AND payment_owner_auth_provision_idempotency_key IS NULL
      AND payment_owner_auth_revoke_idempotency_key IS NULL
      AND payment_owner_opaque_handle IS NULL
      AND payment_owner_auth_invalidation_state IS NULL
      AND payment_owner_auth_expiry_upper_bound IS NULL
      AND payment_owner_expires_at IS NULL
    )
    OR (
      payment_owner_test_user_label IS NOT NULL
      AND payment_owner_test_user_label <> test_user_label
      AND payment_owner_case_correlation IS NOT NULL
      AND payment_owner_case_correlation <> case_correlation
      AND payment_owner_auth_provision_idempotency_key IS NOT NULL
      AND payment_owner_auth_revoke_idempotency_key IS NOT NULL
      AND payment_owner_auth_invalidation_state IS NOT NULL
      AND payment_owner_auth_expiry_upper_bound IS NOT NULL
      AND payment_owner_auth_expiry_upper_bound > provisioning_due_at
      AND (
        (
          payment_owner_auth_invalidation_state = 'UNPROVISIONED'
          AND payment_owner_opaque_handle IS NULL
          AND payment_owner_expires_at IS NULL
        )
        OR (
          payment_owner_auth_invalidation_state IN ('PROVISIONED', 'REVOKED')
          AND payment_owner_opaque_handle IS NOT NULL
          AND payment_owner_expires_at IS NOT NULL
          AND payment_owner_expires_at = payment_owner_auth_expiry_upper_bound
        )
        OR (
          payment_owner_auth_invalidation_state = 'EXPIRY_PROVEN'
          AND (
            (
              payment_owner_opaque_handle IS NULL
              AND payment_owner_expires_at IS NULL
            )
            OR (
              payment_owner_opaque_handle IS NOT NULL
              AND payment_owner_expires_at IS NOT NULL
              AND payment_owner_expires_at = payment_owner_auth_expiry_upper_bound
            )
          )
        )
      )
    )
  );

ALTER TABLE eval_sandbox
  DROP CHECK chk_eval_sandbox_active,
  DROP CHECK chk_eval_sandbox_closed,
  ADD CONSTRAINT chk_eval_sandbox_active CHECK (
    lifecycle_state <> 'ACTIVE'
    OR (
      auth_invalidation_state = 'PROVISIONED'
      AND opaque_handle IS NOT NULL
      AND expires_at IS NOT NULL
      AND activated_at IS NOT NULL
      AND death_reason IS NULL
      AND dead_at IS NULL
      AND closed_at IS NULL
      AND (
        payment_owner_test_user_label IS NULL
        OR (
          payment_owner_auth_invalidation_state = 'PROVISIONED'
          AND payment_owner_opaque_handle IS NOT NULL
          AND payment_owner_expires_at IS NOT NULL
          AND expires_at <= payment_owner_expires_at
        )
      )
    )
  ),
  ADD CONSTRAINT chk_eval_sandbox_closed CHECK (
    closed_at IS NULL
    OR (
      lifecycle_state = 'DEAD'
      AND auth_invalidation_state IN ('REVOKED', 'EXPIRY_PROVEN')
      AND (
        payment_owner_test_user_label IS NULL
        OR payment_owner_auth_invalidation_state IN ('REVOKED', 'EXPIRY_PROVEN')
      )
    )
  );
