package io.citybuddy.commerce.seckill;

import org.apache.rocketmq.client.apis.ClientException;
import org.springframework.scheduling.annotation.Scheduled;

public final class SeckillTransactionResolutionWorker {
  static final int BATCH_SIZE = 32;

  private final SeckillReservationService reservations;
  private final SeckillTransactionCoordinator coordinator;

  public SeckillTransactionResolutionWorker(
      SeckillReservationService reservations, SeckillTransactionCoordinator coordinator) {
    this.reservations = reservations;
    this.coordinator = coordinator;
  }

  @Scheduled(
      fixedDelayString = "${citybuddy.seckill.order.resolution-worker-delay:1000}",
      initialDelayString = "${citybuddy.seckill.order.resolution-worker-initial-delay:1000}")
  public void resolveDueReservations() {
    for (ReservationAdmissionStore.AdmissionHandoff handoff :
        reservations.dueAdmissionHandoffs(BATCH_SIZE)) {
      try {
        coordinator.recover(handoff);
      } catch (ClientException ignored) {
        // Redis retains the handoff so the next scheduled pass can retry the external broker edge.
      }
    }
  }
}
