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
      'CONTEXT_WINDOW',
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
      'ACTION_REJECTED',
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
        'action_completed',
        'action_rejected',
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

ALTER TABLE pending_action_reference
  DROP CHECK chk_pending_action_reference_state;

ALTER TABLE pending_action_reference
  ADD CONSTRAINT chk_pending_action_reference_state
    CHECK (
      state IN ('PENDING', 'CONFIRMING', 'DECLINED', 'EXPIRED', 'CONFIRMED', 'REJECTED')
    );

ALTER TABLE pending_action_reference
  DROP CHECK chk_pending_action_reference_terminal;

ALTER TABLE pending_action_reference
  ADD CONSTRAINT chk_pending_action_reference_terminal CHECK (
    (
      state IN ('PENDING', 'CONFIRMING')
      AND resolved_at IS NULL
      AND resolution_turn_id IS NULL
      AND resolution_trace_id IS NULL
    )
    OR (
      state IN ('DECLINED', 'EXPIRED', 'CONFIRMED', 'REJECTED')
      AND resolved_at IS NOT NULL
      AND resolution_turn_id IS NOT NULL
      AND resolution_trace_id IS NOT NULL
      AND resolution_turn_id <> source_turn_id
    )
  );
