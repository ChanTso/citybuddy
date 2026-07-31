ALTER TABLE support_event
  DROP CHECK chk_support_event_sequence;

ALTER TABLE support_event
  ADD CONSTRAINT chk_support_event_sequence CHECK (
    sequence > 0
    AND (
      (sequence = 1 AND event_type = 'USER_INPUT')
      OR (sequence > 1 AND event_type <> 'USER_INPUT')
    )
    AND event_type IN (
      'USER_INPUT',
      'ROUTING_DECISION',
      'BUDGET_CHARGED',
      'CIRCUIT_OUTCOME',
      'MODEL_OUTCOME',
      'TOOL_LIFECYCLE',
      'TOOL_DENIED',
      'RETRIEVAL_DECISION',
      'ACTION_PREPARED',
      'ACTION_DECLINED',
      'ACTION_EXPIRED',
      'AGENT_OUTCOME',
      'ASSISTANT_RESPONSE',
      'TURN_COMPLETED',
      'TURN_FAILED'
    )
  );

ALTER TABLE support_turn
  DROP CHECK chk_support_turn_terminal;

ALTER TABLE support_turn
  ADD CONSTRAINT chk_support_turn_terminal CHECK (
    (
      state = 'PROCESSING'
      AND response_text IS NULL
      AND outcome IS NULL
      AND failure_code IS NULL
      AND completed_at IS NULL
      AND processing_deadline_at IS NOT NULL
    )
    OR (
      state = 'COMPLETED'
      AND response_text IS NOT NULL
      AND outcome IN (
        'completed',
        'budget_exhausted',
        'provider_denied',
        'retrieval_denied',
        'action_pending',
        'action_declined',
        'action_expired',
        'action_clarification'
      )
      AND failure_code IS NULL
      AND completed_at IS NOT NULL
      AND processing_deadline_at IS NULL
    )
    OR (
      state = 'FAILED'
      AND response_text IS NULL
      AND outcome IS NULL
      AND failure_code IS NOT NULL
      AND completed_at IS NOT NULL
      AND processing_deadline_at IS NULL
    )
  );

CREATE TABLE pending_action_reference (
  pending_action_id CHAR(36) NOT NULL,
  source_turn_id CHAR(36) NOT NULL,
  source_trace_id CHAR(36) NOT NULL,
  conversation_id CHAR(36) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  user_subject VARCHAR(190) NOT NULL,
  sandbox_id VARCHAR(64) NULL,
  action_type VARCHAR(32) NOT NULL,
  argument_commitment CHAR(64) NOT NULL,
  order_id CHAR(36) NOT NULL,
  target_version BIGINT UNSIGNED NOT NULL,
  amount_minor BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) NOT NULL,
  state VARCHAR(16) NOT NULL,
  active_session_id VARCHAR(64)
    GENERATED ALWAYS AS (
      CASE WHEN state = 'PENDING' THEN session_id ELSE NULL END
    ) STORED,
  expires_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  resolved_at TIMESTAMP(6) NULL,
  resolution_turn_id CHAR(36) NULL,
  resolution_trace_id CHAR(36) NULL,
  PRIMARY KEY (pending_action_id),
  UNIQUE KEY uq_pending_action_reference_turn (source_turn_id),
  UNIQUE KEY uq_pending_action_reference_resolution_turn (resolution_turn_id),
  UNIQUE KEY uq_pending_action_reference_active_session (active_session_id),
  KEY ix_pending_action_reference_current (
    session_id,
    user_subject,
    state,
    created_at
  ),
  CONSTRAINT fk_pending_action_reference_turn
    FOREIGN KEY (source_turn_id, source_trace_id, session_id, user_subject)
    REFERENCES support_turn (turn_id, trace_id, session_id, user_subject),
  CONSTRAINT fk_pending_action_reference_resolution_turn
    FOREIGN KEY (resolution_turn_id, resolution_trace_id, session_id, user_subject)
    REFERENCES support_turn (turn_id, trace_id, session_id, user_subject),
  CONSTRAINT chk_pending_action_reference_type
    CHECK (action_type = 'REFUND_REQUEST'),
  CONSTRAINT chk_pending_action_reference_commitment
    CHECK (argument_commitment REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT chk_pending_action_reference_amount CHECK (amount_minor > 0),
  CONSTRAINT chk_pending_action_reference_target_version CHECK (target_version > 0),
  CONSTRAINT chk_pending_action_reference_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
  CONSTRAINT chk_pending_action_reference_state
    CHECK (state IN ('PENDING', 'DECLINED', 'EXPIRED')),
  CONSTRAINT chk_pending_action_reference_terminal CHECK (
    (
      state = 'PENDING'
      AND resolved_at IS NULL
      AND resolution_turn_id IS NULL
      AND resolution_trace_id IS NULL
    )
    OR (
      state IN ('DECLINED', 'EXPIRED')
      AND resolved_at IS NOT NULL
      AND resolution_turn_id IS NOT NULL
      AND resolution_trace_id IS NOT NULL
      AND resolution_turn_id <> source_turn_id
    )
  )
) ENGINE=InnoDB;
