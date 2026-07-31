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

ALTER TABLE pending_action_reference
  DROP CHECK chk_pending_action_reference_state,
  DROP CHECK chk_pending_action_reference_terminal,
  DROP INDEX uq_pending_action_reference_active_session,
  ADD COLUMN confirmation_turn_id CHAR(36) NULL AFTER state,
  ADD COLUMN confirmation_trace_id CHAR(36) NULL AFTER confirmation_turn_id,
  MODIFY COLUMN active_session_id VARCHAR(64)
    GENERATED ALWAYS AS (
      CASE WHEN state IN ('PENDING', 'CONFIRMING') THEN session_id ELSE NULL END
    ) STORED,
  ADD UNIQUE KEY uq_pending_action_reference_confirmation_turn (confirmation_turn_id),
  ADD UNIQUE KEY uq_pending_action_reference_active_session (active_session_id),
  ADD CONSTRAINT fk_pending_action_reference_confirmation_turn
    FOREIGN KEY (confirmation_turn_id, confirmation_trace_id, session_id, user_subject)
    REFERENCES support_turn (turn_id, trace_id, session_id, user_subject),
  ADD CONSTRAINT chk_pending_action_reference_state
    CHECK (state IN ('PENDING', 'CONFIRMING', 'DECLINED', 'EXPIRED', 'CONFIRMED')),
  ADD CONSTRAINT chk_pending_action_reference_terminal CHECK (
    (
      state = 'PENDING'
      AND confirmation_turn_id IS NULL
      AND confirmation_trace_id IS NULL
      AND resolved_at IS NULL
      AND resolution_turn_id IS NULL
      AND resolution_trace_id IS NULL
    )
    OR (
      state = 'CONFIRMING'
      AND confirmation_turn_id IS NOT NULL
      AND confirmation_trace_id IS NOT NULL
      AND resolved_at IS NULL
      AND resolution_turn_id IS NULL
      AND resolution_trace_id IS NULL
    )
    OR (
      state IN ('DECLINED', 'EXPIRED')
      AND confirmation_turn_id IS NULL
      AND confirmation_trace_id IS NULL
      AND resolved_at IS NOT NULL
      AND resolution_turn_id IS NOT NULL
      AND resolution_trace_id IS NOT NULL
      AND resolution_turn_id <> source_turn_id
    )
    OR (
      state = 'CONFIRMED'
      AND confirmation_turn_id IS NOT NULL
      AND confirmation_trace_id IS NOT NULL
      AND resolved_at IS NOT NULL
      AND resolution_turn_id IS NULL
      AND resolution_trace_id IS NULL
      AND confirmation_turn_id <> source_turn_id
    )
  );

CREATE TABLE action_receipt_projection (
  receipt_id CHAR(36) NOT NULL,
  pending_action_id CHAR(36) NOT NULL,
  source_turn_id CHAR(36) NOT NULL,
  source_trace_id CHAR(36) NOT NULL,
  confirmation_turn_id CHAR(36) NOT NULL,
  confirmation_trace_id CHAR(36) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  user_subject VARCHAR(190) NOT NULL,
  sandbox_id VARCHAR(64) NULL,
  action_type VARCHAR(32) NOT NULL,
  argument_commitment CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  order_id CHAR(36) NOT NULL,
  target_version BIGINT UNSIGNED NOT NULL,
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
    target_version > 0
    AND resource_version = 1
    AND amount_minor > 0
    AND currency REGEXP '^[A-Z]{3}$'
    AND published_event_sequence > 1
  )
) ENGINE=InnoDB;
