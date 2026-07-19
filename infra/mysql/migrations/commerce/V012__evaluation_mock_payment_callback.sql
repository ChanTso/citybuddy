ALTER TABLE standard_order
  ADD COLUMN sandbox_id VARCHAR(64) NULL AFTER user_subject,
  ADD COLUMN evaluation_owner_handle VARCHAR(43) NULL AFTER sandbox_id,
  ADD INDEX ix_standard_order_sandbox (sandbox_id, created_at, order_id),
  ADD CONSTRAINT fk_standard_order_eval_sandbox
    FOREIGN KEY (sandbox_id) REFERENCES eval_sandbox (sandbox_id),
  ADD CONSTRAINT chk_standard_order_eval_binding CHECK (
    (sandbox_id IS NULL AND evaluation_owner_handle IS NULL)
    OR (sandbox_id IS NOT NULL AND evaluation_owner_handle IS NOT NULL)
  );

ALTER TABLE mock_payment_attempt
  ADD COLUMN sandbox_id VARCHAR(64) NULL AFTER order_kind,
  ADD INDEX ix_mock_payment_sandbox_created (sandbox_id, created_at, attempt_id),
  ADD CONSTRAINT fk_mock_payment_attempt_eval_sandbox
    FOREIGN KEY (sandbox_id) REFERENCES eval_sandbox (sandbox_id);

ALTER TABLE mock_payment_callback
  ADD COLUMN sandbox_id VARCHAR(64) NULL AFTER callback_correlation_id,
  ADD COLUMN support_session_id VARCHAR(64) NULL AFTER sandbox_id,
  ADD COLUMN trace_id VARCHAR(64) NULL AFTER support_session_id,
  ADD COLUMN operation_id CHAR(64) NULL AFTER trace_id,
  ADD INDEX ix_mock_payment_callback_sandbox (sandbox_id, created_at, callback_event_id),
  ADD CONSTRAINT fk_mock_payment_callback_eval_sandbox
    FOREIGN KEY (sandbox_id) REFERENCES eval_sandbox (sandbox_id),
  ADD CONSTRAINT chk_mock_payment_callback_eval_context CHECK (
    (sandbox_id IS NULL AND support_session_id IS NULL AND trace_id IS NULL
      AND operation_id IS NULL)
    OR
    (sandbox_id IS NOT NULL AND support_session_id IS NOT NULL AND trace_id IS NOT NULL
      AND operation_id IS NOT NULL)
  ),
  ADD CONSTRAINT chk_mock_payment_callback_operation CHECK (
    operation_id IS NULL OR operation_id REGEXP '^[0-9a-f]{64}$'
  );

ALTER TABLE inventory_ledger
  ADD COLUMN sandbox_id VARCHAR(64) NULL AFTER product_id,
  ADD INDEX ix_inventory_ledger_sandbox (sandbox_id, created_at, movement_id),
  ADD CONSTRAINT fk_inventory_ledger_eval_sandbox
    FOREIGN KEY (sandbox_id) REFERENCES eval_sandbox (sandbox_id);

ALTER TABLE eval_commerce_audit_reference
  MODIFY entity_type ENUM('PRODUCT_FIXTURE', 'PAYMENT_CALLBACK') NOT NULL;
