package io.citybuddy.commerce.seckill;

public record SeckillTransactionMessage(
    String eventId,
    String reservationId,
    String activityId,
    String userSubject,
    int quantity,
    long activityProjectionVersion) {

  public static SeckillTransactionMessage from(SeckillReservation reservation) {
    return new SeckillTransactionMessage(
        reservation.reservationId(),
        reservation.reservationId(),
        reservation.activityId(),
        reservation.userSubject(),
        reservation.quantity(),
        reservation.activityProjectionVersion());
  }
}
