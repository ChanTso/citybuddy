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
      'AGENT_OUTCOME',
      'ASSISTANT_RESPONSE',
      'TURN_COMPLETED',
      'TURN_FAILED'
    )
  );
