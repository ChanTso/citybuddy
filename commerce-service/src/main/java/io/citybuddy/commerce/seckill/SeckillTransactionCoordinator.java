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
    try {
      ReservationAdmissionStore.PreAdmission admission =
          reservations.preAdmit(userSubject, activityId, idempotencyKey, request);
      if (admission.decision().state() == ReservationState.REJECTED) {
        return reservations.preAdmissionResult(admission);
      }
      if (admission.replay() && !admission.handoffPending()) {
        return reservations.pollOwned(userSubject, admission.handoff().reservationId()).asReplay();
      }
      ReservationResult result = messaging.submit(admission.handoff(), reservations);
      return admission.replay() ? result.asReplay() : result;
    } catch (ReservationAdmissionStore.AdmissionIndeterminateException exception) {
      throw new SeckillRequestException(503, "UNAVAILABLE", "Transaction admission is unavailable");
    } catch (ClientException exception) {
      throw new SeckillRequestException(503, "UNAVAILABLE", "Transaction admission is unavailable");
    }
  }

  public void recover(ReservationAdmissionStore.AdmissionHandoff handoff) throws ClientException {
    messaging.submit(handoff, reservations);
  }

  public ReservationResult poll(String userSubject, String reservationId) {
    return reservations.pollOwned(userSubject, reservationId);
  }
}
