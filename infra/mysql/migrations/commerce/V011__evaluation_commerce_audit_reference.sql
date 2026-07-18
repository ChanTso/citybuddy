CREATE TABLE eval_commerce_audit_reference (
  sequence_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  audit_reference_id CHAR(64) NOT NULL,
  sandbox_id VARCHAR(64) NOT NULL,
  support_session_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64) NOT NULL,
  operation_id CHAR(64) NOT NULL,
  entity_type ENUM('PRODUCT_FIXTURE') NOT NULL,
  entity_id VARCHAR(64) NOT NULL,
  entity_version BIGINT UNSIGNED NOT NULL,
  outcome ENUM('OBSERVED') NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (sequence_id),
  UNIQUE KEY uq_eval_audit_reference_id (audit_reference_id),
  UNIQUE KEY uq_eval_audit_operation (sandbox_id, operation_id),
  KEY ix_eval_audit_session_page (
    sandbox_id,
    support_session_id,
    sequence_id
  ),
  CONSTRAINT fk_eval_audit_sandbox
    FOREIGN KEY (sandbox_id) REFERENCES eval_sandbox (sandbox_id),
  CONSTRAINT chk_eval_audit_reference_id CHECK (
    audit_reference_id REGEXP '^[0-9a-f]{64}$'
  ),
  CONSTRAINT chk_eval_audit_operation_id CHECK (
    operation_id REGEXP '^[0-9a-f]{64}$'
  ),
  CONSTRAINT chk_eval_audit_entity_version CHECK (entity_version > 0)
) ENGINE=InnoDB;
