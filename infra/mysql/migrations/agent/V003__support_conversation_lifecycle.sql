CREATE TABLE support_conversation (
  conversation_id CHAR(36) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  user_subject VARCHAR(190) NOT NULL,
  state VARCHAR(16) NOT NULL,
  next_turn_sequence BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (conversation_id),
  UNIQUE KEY uq_support_conversation_session (session_id),
  UNIQUE KEY uq_support_conversation_binding (conversation_id, session_id, user_subject),
  CONSTRAINT fk_support_conversation_session
    FOREIGN KEY (session_id) REFERENCES support_session (session_id),
  CONSTRAINT chk_support_conversation_state CHECK (state IN ('ACTIVE', 'CLOSED'))
) ENGINE=InnoDB;

CREATE TABLE support_turn (
  turn_id CHAR(36) NOT NULL,
  conversation_id CHAR(36) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  user_subject VARCHAR(190) NOT NULL,
  trace_id CHAR(36) NOT NULL,
  turn_sequence BIGINT UNSIGNED NOT NULL,
  correlation_key VARCHAR(128) NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  input_text TEXT NOT NULL,
  response_text TEXT NULL,
  state VARCHAR(16) NOT NULL,
  failure_code VARCHAR(64) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  completed_at TIMESTAMP(6) NULL,
  PRIMARY KEY (turn_id),
  UNIQUE KEY uq_support_turn_trace (trace_id),
  UNIQUE KEY uq_support_turn_correlation (session_id, correlation_key),
  UNIQUE KEY uq_support_turn_position (conversation_id, turn_sequence),
  UNIQUE KEY uq_support_turn_binding (turn_id, trace_id, session_id, user_subject),
  CONSTRAINT fk_support_turn_conversation
    FOREIGN KEY (conversation_id, session_id, user_subject)
    REFERENCES support_conversation (conversation_id, session_id, user_subject),
  CONSTRAINT chk_support_turn_sequence CHECK (turn_sequence > 0),
  CONSTRAINT chk_support_turn_state CHECK (state IN ('PROCESSING', 'COMPLETED', 'FAILED')),
  CONSTRAINT chk_support_turn_terminal CHECK (
    (state = 'PROCESSING' AND response_text IS NULL AND failure_code IS NULL AND completed_at IS NULL)
    OR (state = 'COMPLETED' AND response_text IS NOT NULL AND failure_code IS NULL AND completed_at IS NOT NULL)
    OR (state = 'FAILED' AND response_text IS NULL AND failure_code IS NOT NULL AND completed_at IS NOT NULL)
  )
) ENGINE=InnoDB;

CREATE TABLE support_event (
  event_id CHAR(36) NOT NULL,
  turn_id CHAR(36) NOT NULL,
  trace_id CHAR(36) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  user_subject VARCHAR(190) NOT NULL,
  sequence INT UNSIGNED NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  payload_json JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (event_id),
  UNIQUE KEY uq_support_event_trace_sequence (trace_id, sequence),
  UNIQUE KEY uq_support_event_turn_sequence (turn_id, sequence),
  CONSTRAINT fk_support_event_turn
    FOREIGN KEY (turn_id, trace_id, session_id, user_subject)
    REFERENCES support_turn (turn_id, trace_id, session_id, user_subject),
  CONSTRAINT chk_support_event_sequence CHECK (
    (sequence = 1 AND event_type = 'USER_INPUT')
    OR (sequence = 2 AND event_type IN ('ASSISTANT_RESPONSE', 'TURN_FAILED'))
    OR (sequence = 3 AND event_type = 'TURN_COMPLETED')
  )
) ENGINE=InnoDB;

INSERT INTO support_conversation (
  conversation_id,
  session_id,
  user_subject,
  state,
  next_turn_sequence
)
SELECT UUID(), session_id, user_subject, 'ACTIVE', 0
FROM support_session;
