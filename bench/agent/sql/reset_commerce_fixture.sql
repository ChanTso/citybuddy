-- Clears the commerce side of the agent bench fixture.
--
-- Orders, payment attempts and callbacks are keyed per bench user and replay on a rerun, so a
-- fixture left behind by an interrupted run conflicts with the rebuilt one rather than being
-- overwritten: the stored idempotency intent still names the previous order. Deleting in
-- dependency order lets every rerun start from the same state.
--
-- The runtime accounts hold no DELETE grant by design, so this runs as bootstrap.
DELETE r FROM action_receipt r JOIN pending_action p USING (pending_action_id)
  WHERE p.user_subject LIKE 'bench-user-%';
DELETE FROM pending_action WHERE user_subject LIKE 'bench-user-%';
DELETE c FROM mock_payment_callback c JOIN mock_payment_attempt a USING (attempt_id)
  WHERE a.order_id IN (SELECT order_id FROM standard_order WHERE user_subject LIKE 'bench-user-%');
DELETE FROM mock_refund WHERE user_subject LIKE 'bench-user-%';
DELETE FROM mock_payment_attempt WHERE order_id IN
  (SELECT order_id FROM standard_order WHERE user_subject LIKE 'bench-user-%');
DELETE FROM order_idempotency WHERE user_subject LIKE 'bench-user-%';
DELETE FROM standard_order WHERE user_subject LIKE 'bench-user-%';
