package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class SeckillTransactionCoordinatorTest {
  @Test
  void redisRejectionReturnsWithoutTouchingRocketMq() {
    SeckillReservationService reservations = mock(SeckillReservationService.class);
    RocketMqSeckillTransactions messaging = mock(RocketMqSeckillTransactions.class);
    var handoff =
        new ReservationAdmissionStore.AdmissionHandoff(
            "00000000-0000-0000-0000-000000000001",
            "subject",
            "activity",
            "request-key",
            "a".repeat(64),
            1,
            1);
    var admission =
        new ReservationAdmissionStore.PreAdmission(
            handoff,
            new ReservationAdmissionStore.AdmissionDecision(
                ReservationState.REJECTED, ReservationDecisionCode.EXHAUSTED),
            false,
            false);
    ReservationResult expected =
        new ReservationResult(
            handoff.reservationId(),
            handoff.activityId(),
            1,
            1,
            ReservationState.REJECTED,
            ReservationDecisionCode.EXHAUSTED,
            2,
            false,
            false,
            null);
    ReservationRequest request = new ReservationRequest();
    when(reservations.preAdmit("subject", "activity", "request-key", request))
        .thenReturn(admission);
    when(reservations.preAdmissionResult(admission)).thenReturn(expected);
    SeckillTransactionCoordinator coordinator =
        new SeckillTransactionCoordinator(reservations, messaging);

    ReservationResult result = coordinator.submit("subject", "activity", "request-key", request);

    assertThat(result).isEqualTo(expected);
    verifyNoInteractions(messaging);
  }
}
