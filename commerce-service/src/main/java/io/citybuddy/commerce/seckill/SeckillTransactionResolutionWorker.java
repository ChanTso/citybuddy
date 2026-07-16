package io.citybuddy.commerce.seckill;

import org.springframework.scheduling.annotation.Scheduled;

public final class SeckillTransactionResolutionWorker {
  static final int BATCH_SIZE = 32;

  private final SeckillReservationService reservations;

  public SeckillTransactionResolutionWorker(SeckillReservationService reservations) {
    this.reservations = reservations;
  }

  @Scheduled(
      fixedDelayString = "${citybuddy.seckill.order.resolution-worker-delay:1000}",
      initialDelayString = "${citybuddy.seckill.order.resolution-worker-initial-delay:1000}")
  public void resolveDueReservations() {
    reservations.resolveDueReservations(BATCH_SIZE);
  }
}
