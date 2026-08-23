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
        'action_completed',
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

-- CONFIRMING is the claim taken before the irreversible commerce call. Nothing but a
-- confirmation may resolve a claimed reference: a decline or an expiry that won a race against a
-- refund already committed at commerce would record, durably, that the refund did not happen.
ALTER TABLE pending_action_reference
  DROP CHECK chk_pending_action_reference_state;

ALTER TABLE pending_action_reference
  ADD CONSTRAINT chk_pending_action_reference_state
    CHECK (state IN ('PENDING', 'CONFIRMING', 'DECLINED', 'EXPIRED', 'CONFIRMED'));

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
      state IN ('DECLINED', 'EXPIRED', 'CONFIRMED')
      AND resolved_at IS NOT NULL
      AND resolution_turn_id IS NOT NULL
      AND resolution_trace_id IS NOT NULL
      AND resolution_turn_id <> source_turn_id
    )
  );

-- One live action per session covers the claimed state too, or a second action could be prepared
-- in the same session while the first is mid-confirmation.
ALTER TABLE pending_action_reference
  MODIFY COLUMN active_session_id VARCHAR(64)
    GENERATED ALWAYS AS (
      CASE WHEN state IN ('PENDING', 'CONFIRMING') THEN session_id ELSE NULL END
    ) STORED;

-- The commerce ActionReceipt is the authoritative record of the refund; this is the agent's local
-- projection of it, written in the same transaction that resolves the reference and commits the
-- turn. It is insert-only: a receipt that changed would be a second, different truth about a
-- refund that has already happened.
CREATE TABLE action_receipt_projection (
  receipt_id CHAR(36) NOT NULL,
  pending_action_id CHAR(36) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  user_subject VARCHAR(190) NOT NULL,
  turn_id CHAR(36) NOT NULL,
  trace_id CHAR(36) NOT NULL,
  action_type VARCHAR(32) NOT NULL,
  result_state VARCHAR(16) NOT NULL,
  order_id CHAR(36) NOT NULL,
  refund_id CHAR(36) NOT NULL,
  resource_version BIGINT UNSIGNED NOT NULL,
  amount_minor BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) NOT NULL,
  committed_at TIMESTAMP(6) NOT NULL,
  projected_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (receipt_id),
  UNIQUE KEY uq_action_receipt_projection_pending (pending_action_id),
  UNIQUE KEY uq_action_receipt_projection_turn (turn_id),
  UNIQUE KEY uq_action_receipt_projection_refund (refund_id),
  CONSTRAINT fk_action_receipt_projection_pending
    FOREIGN KEY (pending_action_id)
    REFERENCES pending_action_reference (pending_action_id),
  CONSTRAINT fk_action_receipt_projection_turn
    FOREIGN KEY (turn_id, trace_id, session_id, user_subject)
    REFERENCES support_turn (turn_id, trace_id, session_id, user_subject),
  CONSTRAINT chk_action_receipt_projection_type
    CHECK (action_type = 'REFUND_REQUEST'),
  -- The refund is requested, not settled: commerce owns settlement and this column must not be
  -- able to claim otherwise.
  CONSTRAINT chk_action_receipt_projection_result_state
    CHECK (result_state = 'REQUESTED'),
  CONSTRAINT chk_action_receipt_projection_amount CHECK (amount_minor > 0),
  CONSTRAINT chk_action_receipt_projection_version CHECK (resource_version > 0),
  CONSTRAINT chk_action_receipt_projection_currency CHECK (currency REGEXP '^[A-Z]{3}$')
) ENGINE=InnoDB;
