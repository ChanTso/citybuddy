-- Clears the support side of the agent bench fixture: sessions, conversations, turns, the events
-- that carry the boundary evidence, and the retrieval and pending-action records that hang off
-- them. Deleted in dependency order so foreign keys stay satisfied at every step.
-- The receipt projection references the pending action, so it goes first.
DELETE FROM action_receipt_projection WHERE user_subject LIKE 'bench-user-%';
DELETE FROM pending_action_reference WHERE user_subject LIKE 'bench-user-%';
DELETE e FROM support_event e JOIN support_session s USING (session_id)
  WHERE s.user_subject LIKE 'bench-user-%';
DELETE e FROM retrieval_evidence e JOIN retrieval_decision d USING (decision_id)
  WHERE d.user_subject LIKE 'bench-user-%';
DELETE FROM retrieval_decision WHERE user_subject LIKE 'bench-user-%';
DELETE FROM support_feedback WHERE user_subject LIKE 'bench-user-%';
DELETE t FROM support_turn t JOIN support_session s USING (session_id)
  WHERE s.user_subject LIKE 'bench-user-%';
DELETE FROM support_conversation WHERE user_subject LIKE 'bench-user-%';
DELETE FROM support_session WHERE user_subject LIKE 'bench-user-%';
