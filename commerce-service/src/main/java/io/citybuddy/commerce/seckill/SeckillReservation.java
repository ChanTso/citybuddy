package io.citybuddy.commerce.seckill;

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
    long projectionVersion) {}
