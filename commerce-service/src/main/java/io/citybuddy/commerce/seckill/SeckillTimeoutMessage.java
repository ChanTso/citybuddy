package io.citybuddy.commerce.seckill;

import java.time.Instant;

public record SeckillTimeoutMessage(
    String eventId,
    String orderId,
    String reservationId,
    String expectedState,
    long expectedVersion,
    Instant dueAt,
    String correlationId) {

  static SeckillTimeoutMessage from(SeckillOrderRepository.OrderRecord order) {
    return new SeckillTimeoutMessage(
        order.timeoutEventId(),
        order.orderId(),
        order.reservationId(),
        "UNPAID",
        1,
        order.unpaidDeadline(),
        order.transactionEventId());
  }
}
