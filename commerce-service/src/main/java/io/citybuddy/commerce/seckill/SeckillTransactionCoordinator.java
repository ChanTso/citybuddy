package io.citybuddy.commerce.seckill;

import org.apache.rocketmq.client.apis.ClientException;

public final class SeckillTransactionCoordinator {
  private final SeckillReservationService reservations;
  private final RocketMqSeckillTransactions messaging;

  public SeckillTransactionCoordinator(
      SeckillReservationService reservations, RocketMqSeckillTransactions messaging) {
    this.reservations = reservations;
    this.messaging = messaging;
  }

  public ReservationResult submit(
      String userSubject, String activityId, String idempotencyKey, ReservationRequest request) {
    SeckillReservationService.PreparedReservation prepared =
        reservations.prepare(userSubject, activityId, idempotencyKey, request);
    if (prepared.reservation().state() != ReservationState.PENDING) {
      return ReservationResult.from(prepared.reservation(), true);
    }
    try {
      ReservationResult result = messaging.submit(prepared.reservation(), reservations);
      return prepared.existing() ? result.asReplay() : result;
    } catch (ReservationAdmissionStore.AdmissionIndeterminateException exception) {
      return reservations.pollOwned(userSubject, prepared.reservation().reservationId()).asReplay();
    } catch (ClientException exception) {
      throw new SeckillRequestException(503, "UNAVAILABLE", "Transaction admission is unavailable");
    }
  }

  public ReservationResult poll(String userSubject, String reservationId) {
    return reservations.pollOwned(userSubject, reservationId);
  }
}
