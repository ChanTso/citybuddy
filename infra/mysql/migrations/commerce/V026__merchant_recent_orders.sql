ALTER TABLE seckill_order
  ADD INDEX idx_seckill_order_created (created_at, order_id);
