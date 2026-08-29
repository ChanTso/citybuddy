package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.citybuddy.commerce.evaluation.EvaluationSandboxRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommittedPaymentStartResolutionTest {
  private static final String NO_LOCK = "";
  private static final String LOCK = " FOR UPDATE";
  private static final String USER = "payment-owner";
  private static final String ORDER_ID = "00000000-0000-0000-0000-000000000120";
  private static final String KEY = "payment-start-resolution";
  private static final long AMOUNT = 1800;
  private static final String CURRENCY = "AUD";
  private static final String SANDBOX = "sandbox-payment";
  private static final String HANDLE = "A".repeat(43);
  private static final String INTENT =
      EvaluationPaymentCommittedFaces.attemptIntentHash(ORDER_ID, KEY, AMOUNT, CURRENCY, null);

  @Test
  void concealedResolutionStillConsumesBothDeclaredVisibilityLocators() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    when(repository.enumerateStartAttemptVisibility(USER, KEY, NO_LOCK)).thenReturn(List.of());
    when(repository.enumerateStartOrderVisibility(ORDER_ID, USER, null, null))
        .thenReturn(List.of());

    CommittedPaymentTruthResolver.StartCommandResolution resolution =
        new CommittedPaymentTruthResolver(repository).resolveStartCommandLocked(context());

    assertThat(resolution).isInstanceOf(CommittedPaymentTruthResolver.ConcealedStart.class);
    verify(repository).enumerateStartAttemptVisibility(USER, KEY, NO_LOCK);
    verify(repository).enumerateStartOrderVisibility(ORDER_ID, USER, null, null);
  }

  @Test
  void ownedOrderLocatorRoutesAnOwnerDamagedAttemptThroughTheCompleteClosure() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentRepository.OrderTruth order = unpaidOrder(USER);
    MockPaymentRepository.AttemptRecord damaged = pendingAttempt("damaged-owner");
    when(repository.enumerateStartAttemptVisibility(USER, KEY, NO_LOCK)).thenReturn(List.of());
    when(repository.enumerateStartOrderVisibility(ORDER_ID, USER, null, null))
        .thenReturn(List.of(new PaymentStartOrderVisibility.DirectOwner(order)));
    when(repository.enumerateAttemptByOrderClosure(ORDER_ID, NO_LOCK)).thenReturn(List.of(damaged));
    when(repository.enumerateAttemptByOrderClosure(ORDER_ID, LOCK)).thenReturn(List.of(damaged));
    when(repository.enumerateOrderClosure(ORDER_ID, LOCK)).thenReturn(List.of(order));
    when(repository.enumerateAttemptClosure(damaged, LOCK)).thenReturn(List.of(damaged));
    when(repository.discoverCallbackClosure(damaged, "")).thenReturn(List.of());
    when(repository.enumerateLedgerClosure(damaged, order, "")).thenReturn(List.of());

    assertThatThrownBy(
            () ->
                new CommittedPaymentTruthResolver(repository).resolveStartCommandLocked(context()))
        .isInstanceOf(CommittedPaymentIntegrityException.class);

    verify(repository).enumerateStartAttemptVisibility(USER, KEY, NO_LOCK);
    verify(repository).enumerateStartOrderVisibility(ORDER_ID, USER, null, null);
    verify(repository).enumerateAttemptByOrderClosure(ORDER_ID, NO_LOCK);
    verify(repository).enumerateAttemptByOrderClosure(ORDER_ID, LOCK);
  }

  @Test
  void commandLocatorAloneCannotTurnAnOwnerDamagedOrderIntoAReplay() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentRepository.OrderTruth damagedOrder = unpaidOrder("damaged-owner");
    MockPaymentRepository.AttemptRecord attempt = pendingAttempt(USER);
    when(repository.enumerateStartAttemptVisibility(USER, KEY, NO_LOCK))
        .thenReturn(List.of(attempt));
    when(repository.enumerateStartAttemptVisibility(USER, KEY, LOCK)).thenReturn(List.of(attempt));
    when(repository.enumerateStartOrderVisibility(ORDER_ID, USER, null, null))
        .thenReturn(List.of());
    when(repository.enumerateAttemptClosure(attempt, LOCK)).thenReturn(List.of(attempt));
    when(repository.enumerateOrderClosure(ORDER_ID, LOCK)).thenReturn(List.of(damagedOrder));
    when(repository.discoverCallbackClosure(attempt, "")).thenReturn(List.of());
    when(repository.enumerateLedgerClosure(attempt, damagedOrder, "")).thenReturn(List.of());

    assertThatThrownBy(
            () ->
                new CommittedPaymentTruthResolver(repository).resolveStartCommandLocked(context()))
        .isInstanceOf(CommittedPaymentIntegrityException.class);

    verify(repository).enumerateStartAttemptVisibility(USER, KEY, NO_LOCK);
    verify(repository).enumerateStartAttemptVisibility(USER, KEY, LOCK);
    verify(repository).enumerateStartOrderVisibility(ORDER_ID, USER, null, null);
  }

  @Test
  void visibleCommittedOrderWithoutItsAttemptIsIntegrityDamageBeforeIntentComparison() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentRepository.OrderTruth damagedOrder =
        new MockPaymentRepository.OrderTruth(
            "STANDARD",
            ORDER_ID,
            USER,
            null,
            null,
            "payment-product",
            null,
            null,
            null,
            null,
            AMOUNT + 1,
            CURRENCY,
            "PAID",
            2);
    when(repository.enumerateStartAttemptVisibility(USER, KEY, NO_LOCK)).thenReturn(List.of());
    when(repository.enumerateStartOrderVisibility(ORDER_ID, USER, null, null))
        .thenReturn(List.of(new PaymentStartOrderVisibility.DirectOwner(damagedOrder)));
    when(repository.enumerateAttemptByOrderClosure(ORDER_ID, NO_LOCK)).thenReturn(List.of());
    when(repository.enumerateOrderClosure(ORDER_ID, LOCK)).thenReturn(List.of(damagedOrder));
    when(repository.enumerateLedgerReplayClosure(null, ORDER_ID, "")).thenReturn(List.of());

    assertThatThrownBy(
            () ->
                new CommittedPaymentTruthResolver(repository).resolveStartCommandLocked(context()))
        .isInstanceOf(CommittedPaymentIntegrityException.class);
  }

  @Test
  void orderChangeAfterEmptyAttemptDiscoveryRequestsAFreshTransaction() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentRepository.OrderTruth visible = unpaidOrder(USER);
    MockPaymentRepository.OrderTruth current =
        new MockPaymentRepository.OrderTruth(
            visible.orderKind(),
            visible.orderId(),
            visible.userSubject(),
            visible.sandboxId(),
            visible.evaluationOwnerHandle(),
            visible.productId(),
            visible.reservationId(),
            visible.activityId(),
            visible.transactionEventId(),
            visible.quantity(),
            visible.amountMinor(),
            visible.currency(),
            "PAID",
            2);
    when(repository.enumerateStartAttemptVisibility(USER, KEY, NO_LOCK)).thenReturn(List.of());
    when(repository.enumerateStartOrderVisibility(ORDER_ID, USER, null, null))
        .thenReturn(List.of(new PaymentStartOrderVisibility.DirectOwner(visible)));
    when(repository.enumerateAttemptByOrderClosure(ORDER_ID, NO_LOCK)).thenReturn(List.of());
    when(repository.enumerateOrderClosure(ORDER_ID, LOCK)).thenReturn(List.of(current));

    assertThatThrownBy(
            () ->
                new CommittedPaymentTruthResolver(repository).resolveStartCommandLocked(context()))
        .isInstanceOf(PaymentStartObservationChangedException.class);
  }

  @Test
  void bothVisibleLocatorsProduceOnlyTheTypedPendingReplay() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentRepository.OrderTruth order = unpaidOrder(USER);
    MockPaymentRepository.AttemptRecord attempt = pendingAttempt(USER);
    when(repository.enumerateStartAttemptVisibility(USER, KEY, NO_LOCK))
        .thenReturn(List.of(attempt));
    when(repository.enumerateStartAttemptVisibility(USER, KEY, LOCK)).thenReturn(List.of(attempt));
    when(repository.enumerateStartOrderVisibility(ORDER_ID, USER, null, null))
        .thenReturn(List.of(new PaymentStartOrderVisibility.DirectOwner(order)));
    when(repository.enumerateAttemptClosure(attempt, LOCK)).thenReturn(List.of(attempt));
    when(repository.enumerateOrderClosure(ORDER_ID, LOCK)).thenReturn(List.of(order));
    when(repository.discoverCallbackClosure(attempt, "")).thenReturn(List.of());
    when(repository.enumerateLedgerClosure(attempt, order, "")).thenReturn(List.of());

    CommittedPaymentTruthResolver.StartCommandResolution resolution =
        new CommittedPaymentTruthResolver(repository).resolveStartCommandLocked(context());

    assertThat(resolution).isInstanceOf(CommittedPaymentTruthResolver.PendingReplay.class);
    verify(repository).enumerateStartAttemptVisibility(USER, KEY, NO_LOCK);
    verify(repository).enumerateStartAttemptVisibility(USER, KEY, LOCK);
    verify(repository).enumerateStartOrderVisibility(ORDER_ID, USER, null, null);
  }

  @Test
  void discoveredAttemptMustStillBeTheSameRowWhenLocked() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentRepository.AttemptRecord discovered = pendingAttempt(USER);
    MockPaymentRepository.AttemptRecord replacement =
        MockPaymentRepository.AttemptRecord.pending(
            "00000000-0000-0000-0000-000000000131",
            "00000000-0000-0000-0000-000000000132",
            USER,
            ORDER_ID,
            "STANDARD",
            null,
            KEY,
            INTENT,
            AMOUNT,
            CURRENCY);
    when(repository.enumerateStartAttemptVisibility(USER, KEY, NO_LOCK))
        .thenReturn(List.of(discovered));
    when(repository.enumerateStartAttemptVisibility(USER, KEY, LOCK))
        .thenReturn(List.of(replacement));

    assertThatThrownBy(
            () ->
                new CommittedPaymentTruthResolver(repository).resolveStartCommandLocked(context()))
        .isInstanceOf(CommittedPaymentIntegrityException.class);
  }

  @Test
  void bindableFixtureProducesATypedProofBeforeAnyOwnerWrite() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentRepository.OrderTruth order =
        new MockPaymentRepository.OrderTruth(
            "STANDARD",
            ORDER_ID,
            EvaluationSandboxRepository.fixtureOwner(HANDLE),
            SANDBOX,
            HANDLE,
            "payment-product",
            null,
            null,
            null,
            null,
            AMOUNT,
            CURRENCY,
            "UNPAID",
            1);
    PaymentStartOrderVisibility.Classification visibility =
        PaymentStartOrderVisibility.classify(order, USER, SANDBOX, HANDLE);
    CommittedPaymentTruthResolver.StartCommandContext context =
        new CommittedPaymentTruthResolver.StartCommandContext(
            USER,
            SANDBOX,
            HANDLE,
            ORDER_ID,
            KEY,
            EvaluationPaymentCommittedFaces.attemptIntentHash(
                ORDER_ID, KEY, AMOUNT, CURRENCY, SANDBOX),
            AMOUNT,
            CURRENCY);
    when(repository.enumerateStartAttemptVisibility(USER, KEY, NO_LOCK)).thenReturn(List.of());
    when(repository.enumerateStartOrderVisibility(ORDER_ID, USER, SANDBOX, HANDLE))
        .thenReturn(List.of(visibility));
    when(repository.enumerateAttemptByOrderClosure(ORDER_ID, NO_LOCK)).thenReturn(List.of());
    when(repository.enumerateOrderClosure(ORDER_ID, LOCK)).thenReturn(List.of(order));
    when(repository.enumerateLedgerReplayClosure(null, ORDER_ID, "")).thenReturn(List.of());

    CommittedPaymentTruthResolver.StartCommandResolution resolution =
        new CommittedPaymentTruthResolver(repository).resolveStartCommandLocked(context);

    assertThat(resolution)
        .isInstanceOfSatisfying(
            CommittedPaymentTruthResolver.CreateEligible.class,
            eligible -> {
              assertThat(eligible.order()).isEqualTo(order);
              assertThat(eligible.bindingProof()).isPresent();
            });
  }

  private static CommittedPaymentTruthResolver.StartCommandContext context() {
    return new CommittedPaymentTruthResolver.StartCommandContext(
        USER, null, null, ORDER_ID, KEY, INTENT, AMOUNT, CURRENCY);
  }

  private static MockPaymentRepository.AttemptRecord pendingAttempt(String userSubject) {
    return MockPaymentRepository.AttemptRecord.pending(
        "00000000-0000-0000-0000-000000000121",
        "00000000-0000-0000-0000-000000000122",
        userSubject,
        ORDER_ID,
        "STANDARD",
        null,
        KEY,
        INTENT,
        AMOUNT,
        CURRENCY);
  }

  private static MockPaymentRepository.OrderTruth unpaidOrder(String userSubject) {
    return new MockPaymentRepository.OrderTruth(
        "STANDARD",
        ORDER_ID,
        userSubject,
        null,
        null,
        "payment-product",
        null,
        null,
        null,
        null,
        AMOUNT,
        CURRENCY,
        "UNPAID",
        1);
  }
}
