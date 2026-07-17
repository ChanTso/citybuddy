ALTER TABLE support_event
  DROP CHECK chk_support_event_sequence;

ALTER TABLE support_event
  ADD CONSTRAINT chk_support_event_sequence CHECK (
    sequence > 0
    AND event_type IN (
      'USER_INPUT',
      'ROUTING_DECISION',
      'BUDGET_CHARGED',
      'CIRCUIT_OUTCOME',
      'MODEL_OUTCOME',
      'TOOL_LIFECYCLE',
      'TOOL_DENIED',
      'AGENT_OUTCOME',
      'ASSISTANT_RESPONSE',
      'TURN_COMPLETED',
      'TURN_FAILED'
    )
  );

ALTER TABLE support_turn
  ADD COLUMN outcome VARCHAR(32) NULL AFTER response_text;

UPDATE support_turn
SET outcome = 'completed'
WHERE state = 'COMPLETED';

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
    )
    OR (
      state = 'COMPLETED'
      AND response_text IS NOT NULL
      AND outcome IN ('completed', 'budget_exhausted', 'provider_denied')
      AND failure_code IS NULL
      AND completed_at IS NOT NULL
    )
    OR (
      state = 'FAILED'
      AND response_text IS NULL
      AND outcome IS NULL
      AND failure_code IS NOT NULL
      AND completed_at IS NOT NULL
    )
  );
