CREATE TABLE faq_source (
  faq_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  draft_question VARCHAR(500) NOT NULL,
  draft_answer VARCHAR(4000) NOT NULL,
  draft_revision BIGINT UNSIGNED NOT NULL,
  working_state ENUM('DRAFT', 'PUBLISHED') NOT NULL DEFAULT 'DRAFT',
  published_question VARCHAR(500) NULL,
  published_answer VARCHAR(4000) NULL,
  published_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  published_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT chk_faq_id CHECK (REGEXP_LIKE(faq_id, '^[a-z0-9][a-z0-9-]{0,63}$', 'c')),
  CONSTRAINT chk_faq_draft_question CHECK (CHAR_LENGTH(TRIM(draft_question)) > 0),
  CONSTRAINT chk_faq_draft_answer CHECK (CHAR_LENGTH(TRIM(draft_answer)) > 0),
  CONSTRAINT chk_faq_draft_revision CHECK (draft_revision > 0),
  CONSTRAINT chk_faq_publication_shape CHECK (
    (
      published_version = 0
      AND published_question IS NULL
      AND published_answer IS NULL
      AND published_at IS NULL
      AND working_state = 'DRAFT'
    ) OR (
      published_version > 0
      AND published_question IS NOT NULL
      AND published_answer IS NOT NULL
      AND published_at IS NOT NULL
      AND (
        working_state = 'DRAFT'
        OR (
          working_state = 'PUBLISHED'
          AND draft_question = published_question
          AND draft_answer = published_answer
        )
      )
    )
  )
) ENGINE=InnoDB;

CREATE TABLE faq_publication_command (
  idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  faq_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expected_draft_revision BIGINT UNSIGNED NOT NULL,
  expected_published_version BIGINT UNSIGNED NOT NULL,
  source_version BIGINT UNSIGNED NOT NULL,
  intent_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_faq_publication_event UNIQUE (event_id),
  CONSTRAINT uq_faq_publication_version UNIQUE (faq_id, source_version),
  CONSTRAINT fk_faq_publication_source
    FOREIGN KEY (faq_id) REFERENCES faq_source (faq_id),
  CONSTRAINT chk_faq_publication_expected_draft CHECK (expected_draft_revision > 0),
  CONSTRAINT chk_faq_publication_version_step CHECK (
    source_version = expected_published_version + 1
  ),
  CONSTRAINT chk_faq_publication_intent_hash CHECK (
    REGEXP_LIKE(intent_hash, '^[0-9a-f]{64}$', 'c')
  )
) ENGINE=InnoDB;
