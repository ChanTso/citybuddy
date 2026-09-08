-- One equality range preserves retry ordering across PENDING and FAILED without sorting the backlog.
ALTER TABLE seckill_order
  ADD COLUMN timeout_dispatch_ready BOOLEAN GENERATED ALWAYS AS (
    status = 'UNPAID' AND timeout_dispatch_state IN ('PENDING', 'FAILED')
  ) VIRTUAL,
  ADD INDEX idx_seckill_order_dispatch_ready
    (timeout_dispatch_ready, timeout_dispatch_attempts, created_at, order_id);
