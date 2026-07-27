package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.citybuddy.commerce.order.StandardOrderIntentCommitment;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommittedOrderOriginResolverTest {
  @Test
  void validatesProductionStandardAgainstTheSharedIntentCommitment() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentRepository.OrderTruth order = standardOrder(null);
    String intent = StandardOrderIntentCommitment.hash("product-1", 2, 7);
    when(repository.enumerateStandardOrderOrigin("order-1"))
        .thenReturn(
            List.of(
                new MockPaymentRepository.StandardOrderOriginRecord(
                    "user-1", "order-key", intent, "order-1")));

    assertThat(new CommittedOrderOriginResolver(repository).resolve(order))
        .isInstanceOf(CommittedOrderOriginResolver.StandardOrderHashCommitment.class);
  }

  @Test
  void rejectsMissingOrContradictoryStandardOrigin() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentRepository.OrderTruth order = standardOrder(null);
    when(repository.enumerateStandardOrderOrigin("order-1")).thenReturn(List.of());
    assertThatThrownBy(() -> new CommittedOrderOriginResolver(repository).resolve(order))
        .isInstanceOf(CommittedPaymentIntegrityException.class);

    when(repository.enumerateStandardOrderOrigin("order-1"))
        .thenReturn(
            List.of(
                new MockPaymentRepository.StandardOrderOriginRecord(
                    "user-1", "order-key", "0".repeat(64), "order-1")));
    assertThatThrownBy(() -> new CommittedOrderOriginResolver(repository).resolve(order))
        .isInstanceOf(CommittedPaymentIntegrityException.class);
  }

  @Test
  void evaluationStandardSurvivesFixtureCleanupWithoutProductionIdempotency() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);

    assertThat(new CommittedOrderOriginResolver(repository).resolve(standardOrder("sandbox-1")))
        .isInstanceOf(CommittedOrderOriginResolver.EvaluationStandardOrderRoot.class);
    verify(repository, never()).enumerateStandardOrderOrigin("order-1");
  }

  @Test
  void validatesSeckillProductAgainstTheActivityRoot() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentRepository.OrderTruth order = seckillOrder();
    when(repository.enumerateSeckillActivityOrigin("activity-1"))
        .thenReturn(
            List.of(
                new MockPaymentRepository.SeckillActivityOriginRecord("activity-1", "product-1")));
    when(repository.enumerateSeckillReservationOrigin("reservation-1"))
        .thenReturn(
            List.of(
                new MockPaymentRepository.SeckillReservationOriginRecord(
                    "reservation-1", "user-1", "activity-1", 1, "ORDERED", "order-2")));

    assertThat(new CommittedOrderOriginResolver(repository).resolve(order))
        .isInstanceOf(CommittedOrderOriginResolver.SeckillOriginCommitment.class);

    when(repository.enumerateSeckillActivityOrigin("activity-1"))
        .thenReturn(
            List.of(
                new MockPaymentRepository.SeckillActivityOriginRecord("activity-1", "product-2")));
    assertThatThrownBy(() -> new CommittedOrderOriginResolver(repository).resolve(order))
        .isInstanceOf(CommittedPaymentIntegrityException.class);

    when(repository.enumerateSeckillActivityOrigin("activity-1"))
        .thenReturn(
            List.of(
                new MockPaymentRepository.SeckillActivityOriginRecord("activity-1", "product-1")));
    when(repository.enumerateSeckillReservationOrigin("reservation-1"))
        .thenReturn(
            List.of(
                new MockPaymentRepository.SeckillReservationOriginRecord(
                    "reservation-1", "other-user", "activity-1", 1, "ORDERED", "order-2")));
    assertThatThrownBy(() -> new CommittedOrderOriginResolver(repository).resolve(order))
        .isInstanceOf(CommittedPaymentIntegrityException.class);
  }

  private static MockPaymentRepository.OrderTruth standardOrder(String sandboxId) {
    return new MockPaymentRepository.OrderTruth(
        "STANDARD",
        "order-1",
        "user-1",
        sandboxId,
        null,
        "product-1",
        null,
        null,
        null,
        2L,
        sandboxId == null ? 7L : 1L,
        100L,
        200L,
        "AUD",
        "PAID",
        2);
  }

  private static MockPaymentRepository.OrderTruth seckillOrder() {
    return new MockPaymentRepository.OrderTruth(
        "SECKILL",
        "order-2",
        "user-1",
        null,
        null,
        "product-1",
        "reservation-1",
        "activity-1",
        "event-1",
        1L,
        null,
        200L,
        200L,
        "AUD",
        "PAID",
        2);
  }
}
