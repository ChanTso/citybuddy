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
      'ACTION_RECEIPT',
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
        'action_clarification',
        'action_completed'
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
  PRIMARY KEY (pending_action_id),
  UNIQUE KEY uq_pending_action_reference_turn (source_turn_id),
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
  CONSTRAINT chk_pending_action_reference_type
    CHECK (action_type = 'REFUND_REQUEST'),
  CONSTRAINT chk_pending_action_reference_commitment
    CHECK (argument_commitment REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT chk_pending_action_reference_amount CHECK (amount_minor > 0),
  CONSTRAINT chk_pending_action_reference_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
  CONSTRAINT chk_pending_action_reference_state
    CHECK (state IN ('PENDING', 'DECLINED', 'EXPIRED', 'CONFIRMED')),
  CONSTRAINT chk_pending_action_reference_terminal CHECK (
    (state = 'PENDING' AND resolved_at IS NULL)
    OR (state IN ('DECLINED', 'EXPIRED', 'CONFIRMED') AND resolved_at IS NOT NULL)
  )
) ENGINE=InnoDB;

CREATE TABLE action_receipt_projection (
  receipt_id CHAR(36) NOT NULL,
  pending_action_id CHAR(36) NOT NULL,
  source_turn_id CHAR(36) NOT NULL,
  confirmation_turn_id CHAR(36) NOT NULL,
  confirmation_trace_id CHAR(36) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  user_subject VARCHAR(190) NOT NULL,
  sandbox_id VARCHAR(64) NULL,
  action_type VARCHAR(32) NOT NULL,
  argument_commitment CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  order_id CHAR(36) NOT NULL,
  refund_id CHAR(36) NOT NULL,
  resource_version BIGINT UNSIGNED NOT NULL,
  amount_minor BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) NOT NULL,
  committed_at TIMESTAMP(6) NOT NULL,
  receipt_commitment CHAR(64) NOT NULL,
  published_event_sequence INT UNSIGNED NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (receipt_id),
  UNIQUE KEY uq_action_receipt_projection_pending (pending_action_id),
  UNIQUE KEY uq_action_receipt_projection_confirmation_turn (confirmation_turn_id),
  CONSTRAINT fk_action_receipt_projection_pending
    FOREIGN KEY (pending_action_id) REFERENCES pending_action_reference (pending_action_id),
  CONSTRAINT fk_action_receipt_projection_turn
    FOREIGN KEY (confirmation_turn_id, confirmation_trace_id, session_id, user_subject)
    REFERENCES support_turn (turn_id, trace_id, session_id, user_subject),
  CONSTRAINT chk_action_receipt_projection_type
    CHECK (action_type = 'REFUND_REQUEST'),
  CONSTRAINT chk_action_receipt_projection_status CHECK (status = 'REQUESTED'),
  CONSTRAINT chk_action_receipt_projection_commitments CHECK (
    argument_commitment REGEXP '^[0-9a-f]{64}$'
    AND receipt_commitment REGEXP '^[0-9a-f]{64}$'
  ),
  CONSTRAINT chk_action_receipt_projection_values CHECK (
    resource_version = 1
    AND amount_minor > 0
    AND currency REGEXP '^[A-Z]{3}$'
    AND published_event_sequence > 1
  )
) ENGINE=InnoDB;
