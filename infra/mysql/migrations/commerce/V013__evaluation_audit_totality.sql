CREATE TABLE eval_commerce_product_observation (
  observation_id CHAR(64) NOT NULL,
  sandbox_id VARCHAR(64) NOT NULL,
  support_session_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64) NOT NULL,
  operation_id CHAR(64) NOT NULL,
  product_id VARCHAR(64) NOT NULL,
  product_version BIGINT UNSIGNED NOT NULL,
  outcome ENUM('OBSERVED') NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (observation_id),
  UNIQUE KEY uq_eval_product_observation_operation (sandbox_id, operation_id),
  KEY ix_eval_product_observation_sandbox (sandbox_id, created_at, observation_id),
  CONSTRAINT fk_eval_product_observation_sandbox
    FOREIGN KEY (sandbox_id) REFERENCES eval_sandbox (sandbox_id),
  CONSTRAINT chk_eval_product_observation_id CHECK (
    observation_id REGEXP '^[0-9a-f]{64}$'
  ),
  CONSTRAINT chk_eval_product_observation_operation CHECK (
    operation_id REGEXP '^[0-9a-f]{64}$'
  ),
  CONSTRAINT chk_eval_product_observation_version CHECK (product_version > 0)
) ENGINE=InnoDB;

ALTER TABLE eval_commerce_audit_reference
  ADD COLUMN created_at_anchor ENUM('LEGACY_CUTOFF', 'BUSINESS_EVENT')
    NOT NULL DEFAULT 'LEGACY_CUTOFF' AFTER created_at,
  ALTER COLUMN created_at DROP DEFAULT;

ALTER TABLE eval_commerce_audit_reference
  ALTER COLUMN created_at_anchor DROP DEFAULT;

CREATE TABLE eval_commerce_audit_legacy_watermark (
  watermark_key ENUM('V013') NOT NULL,
  commitment_format ENUM('CITYBUDDY_EVAL_AUDIT_LEGACY_LPUTF8_SHA256_CHAIN_V1') NOT NULL,
  legacy_set_digest CHAR(64) NOT NULL,
  cutoff_sequence_id BIGINT UNSIGNED NOT NULL,
  cutoff_audit_reference_id CHAR(64) NULL,
  cutoff_created_at TIMESTAMP(6) NULL,
  legacy_row_count BIGINT UNSIGNED NOT NULL,
  recorded_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (watermark_key),
  CONSTRAINT chk_eval_audit_legacy_digest CHECK (legacy_set_digest REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT chk_eval_audit_legacy_watermark_empty CHECK (
    (cutoff_sequence_id = 0
      AND cutoff_audit_reference_id IS NULL
      AND cutoff_created_at IS NULL
      AND legacy_row_count = 0)
    OR
    (cutoff_sequence_id > 0
      AND cutoff_audit_reference_id IS NOT NULL
      AND cutoff_created_at IS NOT NULL)
  )
) ENGINE=InnoDB COMMENT='V013_DDL_PREPARING';

ALTER TABLE mock_payment_callback
  ALTER COLUMN created_at DROP DEFAULT;

ALTER TABLE eval_commerce_audit_legacy_watermark COMMENT='V013_AWAITING_COMMITMENT';

-- CITYBUDDY_V013_EXACT_GRANT_BARRIER

ALTER TABLE eval_commerce_audit_legacy_watermark COMMENT='V013_COMMITMENT_POPULATING';

-- Commitment format CITYBUDDY_EVAL_AUDIT_LEGACY_LPUTF8_SHA256_CHAIN_V1:
-- fields are sequence_id, audit_reference_id, sandbox_id, support_session_id, trace_id,
-- operation_id, entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor.
-- Each non-null field is UTF-8 encoded as V<byte-length>:<bytes>; and NULL is N;. Timestamps
-- are UTC Unix epoch microseconds in unsigned decimal. A row digest is SHA-256(serialized-row).
-- Starting with SHA-256(commitment-format), rows in sequence_id order roll as
-- SHA-256(previous-digest-bytes || row-digest-bytes). Java reconciliation uses the same format.
SET time_zone = '+00:00';
SET SESSION cte_max_recursion_depth = 1000000;

INSERT INTO eval_commerce_audit_legacy_watermark
  (watermark_key, commitment_format, legacy_set_digest, cutoff_sequence_id,
   cutoff_audit_reference_id, cutoff_created_at, legacy_row_count, recorded_at)
WITH RECURSIVE legacy_rows AS (
  SELECT
    ROW_NUMBER() OVER (ORDER BY sequence_id) AS ordinal,
    SHA2(CONCAT(
      'V', LENGTH(CONVERT(CAST(sequence_id AS CHAR) USING utf8mb4)), ':',
        CONVERT(CAST(sequence_id AS CHAR) USING utf8mb4), ';',
      'V', LENGTH(CONVERT(audit_reference_id USING utf8mb4)), ':',
        CONVERT(audit_reference_id USING utf8mb4), ';',
      'V', LENGTH(CONVERT(sandbox_id USING utf8mb4)), ':',
        CONVERT(sandbox_id USING utf8mb4), ';',
      'V', LENGTH(CONVERT(support_session_id USING utf8mb4)), ':',
        CONVERT(support_session_id USING utf8mb4), ';',
      'V', LENGTH(CONVERT(trace_id USING utf8mb4)), ':',
        CONVERT(trace_id USING utf8mb4), ';',
      'V', LENGTH(CONVERT(operation_id USING utf8mb4)), ':',
        CONVERT(operation_id USING utf8mb4), ';',
      'V', LENGTH(CONVERT(entity_type USING utf8mb4)), ':',
        CONVERT(entity_type USING utf8mb4), ';',
      'V', LENGTH(CONVERT(entity_id USING utf8mb4)), ':',
        CONVERT(entity_id USING utf8mb4), ';',
      'V', LENGTH(CONVERT(CAST(entity_version AS CHAR) USING utf8mb4)), ':',
        CONVERT(CAST(entity_version AS CHAR) USING utf8mb4), ';',
      'V', LENGTH(CONVERT(outcome USING utf8mb4)), ':',
        CONVERT(outcome USING utf8mb4), ';',
      'V', LENGTH(CONVERT(CAST(CAST(UNIX_TIMESTAMP(created_at) * 1000000 AS UNSIGNED) AS CHAR)
        USING utf8mb4)), ':',
        CONVERT(CAST(CAST(UNIX_TIMESTAMP(created_at) * 1000000 AS UNSIGNED) AS CHAR)
        USING utf8mb4), ';',
      'V', LENGTH(CONVERT(created_at_anchor USING utf8mb4)), ':',
        CONVERT(created_at_anchor USING utf8mb4), ';'
    ), 256) AS row_digest
  FROM eval_commerce_audit_reference
  WHERE created_at_anchor = 'LEGACY_CUTOFF'
), legacy_chain (ordinal, set_digest) AS (
  SELECT CAST(0 AS UNSIGNED),
         SHA2('CITYBUDDY_EVAL_AUDIT_LEGACY_LPUTF8_SHA256_CHAIN_V1', 256)
  UNION ALL
  SELECT row_value.ordinal,
         SHA2(CONCAT(UNHEX(chain.set_digest), UNHEX(row_value.row_digest)), 256)
  FROM legacy_chain chain
  JOIN legacy_rows row_value ON row_value.ordinal = chain.ordinal + 1
)
SELECT
  'V013',
  'CITYBUDDY_EVAL_AUDIT_LEGACY_LPUTF8_SHA256_CHAIN_V1',
  (SELECT set_digest FROM legacy_chain ORDER BY ordinal DESC LIMIT 1),
  COALESCE(MAX(sequence_id), 0),
  (SELECT audit_reference_id
     FROM eval_commerce_audit_reference
    ORDER BY sequence_id DESC
    LIMIT 1),
  MAX(created_at),
  COUNT(*),
  CURRENT_TIMESTAMP(6)
FROM eval_commerce_audit_reference;

ALTER TABLE eval_commerce_audit_legacy_watermark COMMENT='V013_COMMITMENT_SEALED';
