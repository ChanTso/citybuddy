package io.citybuddy.commerce.seckill;

import java.time.Instant;

public record SeckillReservation(
    String reservationId,
    String userSubject,
    String activityId,
    String idempotencyKey,
    String intentHash,
    int quantity,
    long activityProjectionVersion,
    ReservationState state,
    ReservationDecisionCode decisionCode,
    long projectionVersion,
    String orderId,
    Instant transactionResolutionDueAt) {

  public SeckillReservation(
      String reservationId,
      String userSubject,
      String activityId,
      String idempotencyKey,
      String intentHash,
      int quantity,
      long activityProjectionVersion,
      ReservationState state,
      ReservationDecisionCode decisionCode,
      long projectionVersion,
      String orderId) {
    this(
        reservationId,
        userSubject,
        activityId,
        idempotencyKey,
        intentHash,
        quantity,
        activityProjectionVersion,
        state,
        decisionCode,
        projectionVersion,
        orderId,
        null);
  }

  public SeckillReservation(
      String reservationId,
      String userSubject,
      String activityId,
      String idempotencyKey,
      String intentHash,
      int quantity,
      long activityProjectionVersion,
      ReservationState state,
      ReservationDecisionCode decisionCode,
      long projectionVersion) {
    this(
        reservationId,
        userSubject,
        activityId,
        idempotencyKey,
        intentHash,
        quantity,
        activityProjectionVersion,
        state,
        decisionCode,
        projectionVersion,
        null,
        null);
  }
}
