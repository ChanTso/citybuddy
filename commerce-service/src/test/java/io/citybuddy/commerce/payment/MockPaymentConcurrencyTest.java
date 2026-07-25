package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.citybuddy.commerce.evaluation.EvaluationSandboxRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;

class MockPaymentConcurrencyTest {
  private static final String USER = "payment-owner";
  private static final String ORDER_ID = "00000000-0000-0000-0000-000000000120";
  private static final String KEY = "payment-concurrency";
  private static final MockPaymentRequest REQUEST = new MockPaymentRequest(1800L, "AUD", null);

  @Test
  void repeatedRealMysqlLockCodesBecomeAttributedIndeterminateWithoutMutation() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentTransactions transactions = executingTransactions();
    when(repository.enumerateStartAttemptVisibility(USER, KEY, " FOR UPDATE"))
        .thenThrow(lockFailure(1205));
    MockPaymentService service =
        new MockPaymentService(repository, transactions, Clock.systemUTC());

    assertThatThrownBy(() -> service.start(USER, ORDER_ID, KEY, REQUEST))
        .isInstanceOfSatisfying(
            MockPaymentException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(429);
              assertThat(exception.category()).isEqualTo("INDETERMINATE");
              assertThat(exception.reason())
                  .isEqualTo(
                      MockPaymentRejectionReason.PAYMENT_CONCURRENCY_OBSERVATION_INDETERMINATE);
            });

    verify(repository, times(3)).enumerateStartAttemptVisibility(USER, KEY, " FOR UPDATE");
    verify(repository, never()).insertAttempt(any());
  }

  @Test
  void siblingPendingTruthReturnsWithoutASecondMutation() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentTransactions transactions = mock(MockPaymentTransactions.class);
    MockPaymentResult pending =
        new MockPaymentResult(
            "attempt", "correlation", ORDER_ID, "STANDARD", 1800, "AUD", "PENDING", true);
    when(transactions.mutate(
            eq(MockPaymentTransactions.Entry.START_INITIAL_MUTATION), any(Supplier.class)))
        .thenThrow(lockFailure(1205));
    when(transactions.observe(
            eq(MockPaymentTransactions.Entry.START_TRUTH_OBSERVATION), any(Supplier.class)))
        .thenReturn(MockPaymentService.CompetitionObservation.found(pending));
    MockPaymentService service =
        new MockPaymentService(repository, transactions, Clock.systemUTC());

    assertThat(service.start(USER, ORDER_ID, KEY, REQUEST)).isEqualTo(pending);

    verify(transactions, never())
        .mutate(eq(MockPaymentTransactions.Entry.START_FINAL_MUTATION), any(Supplier.class));
    verify(repository, never()).insertAttempt(any());
  }

  @Test
  void siblingCommittedTruthPrecedesChangedSandboxLiveness() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentTransactions transactions = mock(MockPaymentTransactions.class);
    EvaluationSandboxRepository sandboxes = mock(EvaluationSandboxRepository.class);
    String sandboxId = "sandbox-payment";
    MockPaymentResult committed =
        new MockPaymentResult(
            "attempt", "correlation", ORDER_ID, "STANDARD", 1800, "AUD", "SUCCEEDED", true);
    when(transactions.mutate(
            eq(MockPaymentTransactions.Entry.START_INITIAL_MUTATION), any(Supplier.class)))
        .thenThrow(deadlockFailure());
    when(transactions.observe(
            eq(MockPaymentTransactions.Entry.START_TRUTH_OBSERVATION), any(Supplier.class)))
        .thenReturn(MockPaymentService.CompetitionObservation.found(committed));
    MockPaymentService service =
        new MockPaymentService(repository, transactions, Clock.systemUTC(), sandboxes);

    assertThat(service.start(USER, sandboxId, ORDER_ID, KEY, REQUEST)).isEqualTo(committed);
    verifyNoInteractions(sandboxes);
    verify(repository, never()).insertAttempt(any());
  }

  @Test
  void confirmedAbsenceAuthorizesExactlyOneFinalMutation() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentTransactions transactions = mock(MockPaymentTransactions.class);
    MockPaymentResult created =
        new MockPaymentResult(
            "attempt", "correlation", ORDER_ID, "STANDARD", 1800, "AUD", "PENDING", false);
    when(transactions.mutate(
            eq(MockPaymentTransactions.Entry.START_INITIAL_MUTATION), any(Supplier.class)))
        .thenThrow(lockFailure(1205));
    when(transactions.observe(
            eq(MockPaymentTransactions.Entry.START_TRUTH_OBSERVATION), any(Supplier.class)))
        .thenReturn(MockPaymentService.CompetitionObservation.confirmedAbsent());
    when(transactions.mutate(
            eq(MockPaymentTransactions.Entry.START_FINAL_MUTATION), any(Supplier.class)))
        .thenReturn(created);
    MockPaymentService service =
        new MockPaymentService(repository, transactions, Clock.systemUTC());

    assertThat(service.start(USER, ORDER_ID, KEY, REQUEST)).isEqualTo(created);

    verify(transactions)
        .mutate(eq(MockPaymentTransactions.Entry.START_FINAL_MUTATION), any(Supplier.class));
    verify(transactions, never())
        .observe(eq(MockPaymentTransactions.Entry.START_FINAL_OBSERVATION), any(Supplier.class));
  }

  @Test
  void finalCompetitionCanOnlyObserveASiblingAndCannotMutateAgain() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentTransactions transactions = mock(MockPaymentTransactions.class);
    MockPaymentResult sibling =
        new MockPaymentResult(
            "attempt", "correlation", ORDER_ID, "STANDARD", 1800, "AUD", "PENDING", true);
    when(transactions.mutate(
            eq(MockPaymentTransactions.Entry.START_INITIAL_MUTATION), any(Supplier.class)))
        .thenThrow(lockFailure(1205));
    when(transactions.observe(
            eq(MockPaymentTransactions.Entry.START_TRUTH_OBSERVATION), any(Supplier.class)))
        .thenReturn(MockPaymentService.CompetitionObservation.confirmedAbsent());
    when(transactions.mutate(
            eq(MockPaymentTransactions.Entry.START_FINAL_MUTATION), any(Supplier.class)))
        .thenThrow(new org.springframework.dao.DuplicateKeyException("controlled sibling"));
    when(transactions.observe(
            eq(MockPaymentTransactions.Entry.START_FINAL_OBSERVATION), any(Supplier.class)))
        .thenReturn(MockPaymentService.CompetitionObservation.found(sibling));
    MockPaymentService service =
        new MockPaymentService(repository, transactions, Clock.systemUTC());

    assertThat(service.start(USER, ORDER_ID, KEY, REQUEST)).isEqualTo(sibling);

    verify(transactions)
        .mutate(eq(MockPaymentTransactions.Entry.START_FINAL_MUTATION), any(Supplier.class));
    verify(transactions)
        .observe(eq(MockPaymentTransactions.Entry.START_FINAL_OBSERVATION), any(Supplier.class));
  }

  @Test
  void finalObservationFailureIsIndeterminateAndNeverStartsASecondFinalMutation() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentTransactions transactions = mock(MockPaymentTransactions.class);
    when(transactions.mutate(
            eq(MockPaymentTransactions.Entry.START_INITIAL_MUTATION), any(Supplier.class)))
        .thenThrow(lockFailure(1205));
    when(transactions.observe(
            eq(MockPaymentTransactions.Entry.START_TRUTH_OBSERVATION), any(Supplier.class)))
        .thenReturn(MockPaymentService.CompetitionObservation.confirmedAbsent());
    when(transactions.mutate(
            eq(MockPaymentTransactions.Entry.START_FINAL_MUTATION), any(Supplier.class)))
        .thenThrow(lockFailure(1213));
    when(transactions.observe(
            eq(MockPaymentTransactions.Entry.START_FINAL_OBSERVATION), any(Supplier.class)))
        .thenThrow(lockFailure(1205));
    MockPaymentService service =
        new MockPaymentService(repository, transactions, Clock.systemUTC());

    assertThatThrownBy(() -> service.start(USER, ORDER_ID, KEY, REQUEST))
        .isInstanceOfSatisfying(
            MockPaymentException.class, exception -> assertThat(exception.status()).isEqualTo(429));
    verify(transactions)
        .mutate(eq(MockPaymentTransactions.Entry.START_FINAL_MUTATION), any(Supplier.class));
  }

  @Test
  void onlyCompletedVisibilityObservationCanConceal() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentTransactions transactions = executingTransactions();
    when(repository.enumerateStartAttemptVisibility(USER, KEY, " FOR UPDATE"))
        .thenThrow(lockFailure(1205))
        .thenReturn(List.of());
    when(repository.enumerateStartOrderVisibility(ORDER_ID, USER, null, " FOR UPDATE"))
        .thenReturn(List.of());
    MockPaymentService service =
        new MockPaymentService(repository, transactions, Clock.systemUTC());

    assertThatThrownBy(() -> service.start(USER, ORDER_ID, KEY, REQUEST))
        .isInstanceOfSatisfying(
            MockPaymentException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(404);
              assertThat(exception.reason())
                  .isEqualTo(MockPaymentRejectionReason.CONCEALED_NOT_FOUND);
            });
    verify(repository, never()).insertAttempt(any());
  }

  @Test
  void resourceAndProgrammerFailuresAreNotReclassifiedAsCompetition() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentTransactions unavailable = mock(MockPaymentTransactions.class);
    when(unavailable.mutate(
            eq(MockPaymentTransactions.Entry.START_INITIAL_MUTATION), any(Supplier.class)))
        .thenThrow(new DataAccessResourceFailureException("controlled outage"));
    MockPaymentTransactions programmer = mock(MockPaymentTransactions.class);
    when(programmer.mutate(
            eq(MockPaymentTransactions.Entry.START_INITIAL_MUTATION), any(Supplier.class)))
        .thenThrow(new IllegalStateException("controlled programmer failure"));

    assertThatThrownBy(
            () ->
                new MockPaymentService(repository, unavailable, Clock.systemUTC())
                    .start(USER, ORDER_ID, KEY, REQUEST))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThatThrownBy(
            () ->
                new MockPaymentService(repository, programmer, Clock.systemUTC())
                    .start(USER, ORDER_ID, KEY, REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("programmer");
  }

  @Test
  void callbackCompetitionAlsoEndsAsAttributedIndeterminate() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentTransactions transactions = executingTransactions();
    when(repository.findAttemptByCorrelationForUpdate(any())).thenThrow(lockFailure(1205));
    when(repository.findAttemptByCorrelation(any())).thenThrow(lockFailure(1213));
    MockPaymentService service =
        new MockPaymentService(repository, transactions, Clock.systemUTC());
    MockPaymentCallbackRequest callback =
        new MockPaymentCallbackRequest(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            1800L,
            "AUD",
            "SUCCEEDED");

    assertThatThrownBy(() -> service.callback("callback-concurrency", callback))
        .isInstanceOfSatisfying(
            MockPaymentException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(429);
              assertThat(exception.reason())
                  .isEqualTo(
                      MockPaymentRejectionReason.PAYMENT_CONCURRENCY_OBSERVATION_INDETERMINATE);
            });
    verify(repository, atLeastOnce()).findAttemptByCorrelation(callback.callbackCorrelationId());
    verify(repository, never()).insertCallback(any(), any());
  }

  @Test
  void completedCallbackTruthAbsenceHasItsOwnAttribution() {
    MockPaymentRepository repository = mock(MockPaymentRepository.class);
    MockPaymentTransactions transactions = executingTransactions();
    MockPaymentService service =
        new MockPaymentService(repository, transactions, Clock.systemUTC());
    MockPaymentCallbackRequest callback =
        new MockPaymentCallbackRequest(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            1800L,
            "AUD",
            "SUCCEEDED");

    assertThatThrownBy(() -> service.callback("callback-unknown", callback))
        .isInstanceOfSatisfying(
            MockPaymentException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(404);
              assertThat(exception.reason())
                  .isEqualTo(MockPaymentRejectionReason.CALLBACK_TRUTH_NOT_FOUND);
            });
    verify(repository, never()).insertCallback(any(), any());
  }

  private static MockPaymentTransactions executingTransactions() {
    MockPaymentTransactions transactions = mock(MockPaymentTransactions.class);
    when(transactions.mutate(any(), any(Supplier.class)))
        .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
    when(transactions.observe(any(), any(Supplier.class)))
        .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
    return transactions;
  }

  private static CannotAcquireLockException lockFailure(int errorCode) {
    return new CannotAcquireLockException(
        "controlled MySQL lock failure",
        new SQLException("controlled MySQL lock failure", "40001", errorCode));
  }

  private static DeadlockLoserDataAccessException deadlockFailure() {
    return new DeadlockLoserDataAccessException(
        "controlled MySQL deadlock", new SQLException("controlled MySQL deadlock", "40001", 1213));
  }
}
