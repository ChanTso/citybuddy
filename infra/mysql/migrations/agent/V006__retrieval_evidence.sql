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
        'retrieval_denied'
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

CREATE TABLE retrieval_decision (
  decision_id CHAR(36) NOT NULL,
  turn_id CHAR(36) NOT NULL,
  trace_id CHAR(36) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  user_subject VARCHAR(190) NOT NULL,
  index_version VARCHAR(64) NOT NULL,
  calibration_version VARCHAR(64) NOT NULL,
  sufficiency_outcome VARCHAR(16) NOT NULL,
  reason_code VARCHAR(32) NOT NULL,
  candidate_count TINYINT UNSIGNED NOT NULL,
  evidence_count TINYINT UNSIGNED NOT NULL,
  top_score DECIMAL(9, 8) NULL,
  top_margin DECIMAL(9, 8) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (decision_id),
  UNIQUE KEY uq_retrieval_decision_turn (turn_id),
  UNIQUE KEY uq_retrieval_decision_trace (trace_id),
  CONSTRAINT fk_retrieval_decision_turn
    FOREIGN KEY (turn_id, trace_id, session_id, user_subject)
    REFERENCES support_turn (turn_id, trace_id, session_id, user_subject),
  CONSTRAINT chk_retrieval_decision_index
    CHECK (index_version REGEXP '^knowledge_docs_v[1-9][0-9]*$'),
  CONSTRAINT chk_retrieval_decision_calibration
    CHECK (calibration_version REGEXP '^cb091-calibration-v[1-9][0-9]*$'),
  CONSTRAINT chk_retrieval_decision_outcome
    CHECK (sufficiency_outcome IN ('SUFFICIENT', 'INSUFFICIENT')),
  CONSTRAINT chk_retrieval_decision_reason CHECK (
    (sufficiency_outcome = 'SUFFICIENT' AND reason_code = 'sufficient')
    OR (
      sufficiency_outcome = 'INSUFFICIENT'
      AND reason_code IN (
        'empty_candidates',
        'below_threshold',
        'ambiguous_margin',
        'reranker_denied'
      )
    )
  ),
  CONSTRAINT chk_retrieval_decision_counts CHECK (
    candidate_count <= 5
    AND evidence_count <= 3
    AND (
      (sufficiency_outcome = 'SUFFICIENT' AND evidence_count > 0)
      OR (sufficiency_outcome = 'INSUFFICIENT' AND evidence_count = 0)
    )
  ),
  CONSTRAINT chk_retrieval_decision_scores CHECK (
    (top_score IS NULL OR top_score BETWEEN 0 AND 1)
    AND (top_margin IS NULL OR top_margin BETWEEN 0 AND 1)
  )
) ENGINE=InnoDB;

CREATE TABLE retrieval_evidence (
  evidence_id CHAR(36) NOT NULL,
  decision_id CHAR(36) NOT NULL,
  evidence_rank TINYINT UNSIGNED NOT NULL,
  source_id VARCHAR(128) NOT NULL,
  chunk_id VARCHAR(128) NOT NULL,
  source_version BIGINT UNSIGNED NOT NULL,
  doc_type VARCHAR(16) NOT NULL,
  title VARCHAR(200) NOT NULL,
  excerpt VARCHAR(600) NOT NULL,
  rerank_score DECIMAL(9, 8) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (evidence_id),
  UNIQUE KEY uq_retrieval_evidence_rank (decision_id, evidence_rank),
  UNIQUE KEY uq_retrieval_evidence_source (
    decision_id,
    source_id,
    source_version,
    chunk_id
  ),
  CONSTRAINT fk_retrieval_evidence_decision
    FOREIGN KEY (decision_id) REFERENCES retrieval_decision (decision_id),
  CONSTRAINT chk_retrieval_evidence_rank CHECK (evidence_rank BETWEEN 1 AND 3),
  CONSTRAINT chk_retrieval_evidence_source_version CHECK (source_version > 0),
  CONSTRAINT chk_retrieval_evidence_doc_type CHECK (doc_type IN ('faq', 'product')),
  CONSTRAINT chk_retrieval_evidence_text CHECK (
    CHAR_LENGTH(title) BETWEEN 1 AND 200
    AND CHAR_LENGTH(excerpt) BETWEEN 1 AND 600
  ),
  CONSTRAINT chk_retrieval_evidence_score CHECK (rerank_score BETWEEN 0 AND 1)
) ENGINE=InnoDB;
