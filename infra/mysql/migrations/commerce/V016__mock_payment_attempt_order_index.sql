ALTER TABLE mock_payment_attempt
  DROP INDEX uq_mock_payment_order,
  ADD CONSTRAINT uq_mock_payment_order UNIQUE (order_id, order_kind);
