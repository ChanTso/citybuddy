ALTER TABLE support_turn
  ADD UNIQUE KEY uq_support_turn_feedback_binding (trace_id, session_id, user_subject);

CREATE TABLE support_feedback (
  feedback_id CHAR(36) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  user_subject VARCHAR(190) NOT NULL,
  trace_id CHAR(36) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  rating VARCHAR(16) NOT NULL,
  comment_text VARCHAR(1000) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (feedback_id),
  UNIQUE KEY uq_support_feedback_intent (session_id, idempotency_key),
  CONSTRAINT fk_support_feedback_turn
    FOREIGN KEY (trace_id, session_id, user_subject)
    REFERENCES support_turn (trace_id, session_id, user_subject),
  CONSTRAINT chk_support_feedback_rating CHECK (rating IN ('POSITIVE', 'NEGATIVE')),
  CONSTRAINT chk_support_feedback_comment CHECK (
    comment_text IS NULL OR CHAR_LENGTH(comment_text) BETWEEN 1 AND 1000
  )
) ENGINE=InnoDB;
