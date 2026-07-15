package io.citybuddy.commerce.seckill;

public record ReservationResult(
    String reservationId,
    String activityId,
    int quantity,
    long activityProjectionVersion,
    ReservationState state,
    ReservationDecisionCode decisionCode,
    long projectionVersion,
    boolean replay,
    boolean durableOrderCreated) {

  static ReservationResult from(SeckillReservation reservation, boolean replay) {
    return new ReservationResult(
        reservation.reservationId(),
        reservation.activityId(),
        reservation.quantity(),
        reservation.activityProjectionVersion(),
        reservation.state(),
        reservation.decisionCode(),
        reservation.projectionVersion(),
        replay,
        false);
  }
}
