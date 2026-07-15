CREATE TABLE support_session (
  session_id VARCHAR(64) NOT NULL,
  user_subject VARCHAR(190) NOT NULL,
  sandbox_id VARCHAR(64) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (session_id),
  KEY ix_support_session_owner (user_subject),
  CONSTRAINT chk_support_session_production
    CHECK (sandbox_id IS NULL OR CHAR_LENGTH(sandbox_id) > 0)
) ENGINE=InnoDB;
