package io.citybuddy.commerce.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.citybuddy.commerce.action.ActionRepository.ActionReceiptRecord;
import io.citybuddy.commerce.action.ActionRepository.PendingActionRecord;
import io.citybuddy.commerce.evaluation.EvaluationSandboxAccess;
import io.citybuddy.commerce.refund.RefundRepository.RefundIntegrityException;
import io.citybuddy.commerce.refund.RefundService;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

class ActionConcurrencyTest {
  private static final String ACTION = "00000000-0000-0000-0000-000000000118";
  private ActionRepository repository;
  private RefundService refunds;
  private ActionTransactions transactions;
  private ActionService service;

  @BeforeEach
  void setUp() {
    repository = mock(ActionRepository.class);
    refunds = mock(RefundService.class);
    transactions = mock(ActionTransactions.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<EvaluationSandboxAccess> sandboxAccess = mock(ObjectProvider.class);
    service =
        new ActionService(
            repository,
            refunds,
            transactions,
            new ActionProperties(
                "refund:create", Duration.ofMinutes(15), 1, 2, Duration.ofMillis(1)),
            Clock.systemUTC(),
            sandboxAccess);
  }

  @Test
  void repeatedMysqlContentionEndsAsAttributedIndeterminateWithoutMutation() {
    CannotAcquireLockException lock = mysqlFailure(1205);
    when(transactions.maximumObservationAttempts()).thenReturn(2);
    when(transactions.pause(1)).thenReturn(true);
    doThrow(lock)
        .when(transactions)
        .mutate(eq(ActionTransactions.Entry.CONFIRM_INITIAL_MUTATION), any());
    doThrow(lock)
        .when(transactions)
        .observe(eq(ActionTransactions.Entry.CONFIRM_TRUTH_OBSERVATION), any());

    assertThatThrownBy(() -> service.confirm(context(), ACTION))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(429);
              assertThat(exception.category()).isEqualTo("INDETERMINATE");
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_CONCURRENCY_OBSERVATION_INDETERMINATE);
            });
    verifyNoInteractions(repository, refunds);
  }

  @Test
  void trueResourceFailureIsUnavailableAndNotContention() {
    DataAccessResourceFailureException unavailable =
        new DataAccessResourceFailureException("controlled outage");
    doThrow(unavailable)
        .when(transactions)
        .mutate(eq(ActionTransactions.Entry.CONFIRM_INITIAL_MUTATION), any());

    assertThatThrownBy(() -> service.confirm(context(), ACTION))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(503);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_DEPENDENCY_UNAVAILABLE);
            });
    verifyNoInteractions(repository, refunds);
  }

  @Test
  void unexpectedPrepareDuplicateIsNotReclassifiedAsCompetition() {
    DuplicateKeyException unexpected =
        new DuplicateKeyException("controlled unexpected prepare duplicate");
    doThrow(unexpected)
        .when(transactions)
        .mutate(eq(ActionTransactions.Entry.PREPARE_INITIAL_MUTATION), any());

    assertThatThrownBy(
            () ->
                service.prepare(
                    context(),
                    new PrepareActionCommand(
                        "REFUND_REQUEST", "00000000-0000-0000-0000-000000000123", 500L, "AUD")))
        .isSameAs(unexpected);
    verifyNoInteractions(repository, refunds);
  }

  @Test
  void invalidSupportSessionsAreRejectedBeforeActionWorkWithoutTrimming() {
    for (String session :
        java.util.List.of(
            " support-session",
            "support-session ",
            "support session",
            "-" + "A".repeat(41),
            "_" + "A".repeat(43))) {
      ActionRequestContext invalid =
          new ActionRequestContext(
              "action-owner",
              session,
              "trace-118",
              "00000000-0000-0000-0000-000000000119",
              null,
              "refund:create");

      assertThatThrownBy(
              () ->
                  service.prepare(
                      invalid,
                      new PrepareActionCommand(
                          "REFUND_REQUEST", "00000000-0000-0000-0000-000000000123", 500L, "AUD")))
          .isInstanceOfSatisfying(
              ActionException.class,
              exception -> {
                assertThat(exception.status()).isEqualTo(400);
                assertThat(exception.category()).isEqualTo("VALIDATION");
              });
    }

    verifyNoInteractions(repository, refunds, transactions);
  }

  @Test
  void refundProgrammerFailureRemainsVisible() {
    IllegalStateException programmerFailure =
        new IllegalStateException("controlled missing Action transaction");
    executePrepareMutation();
    when(refunds.prepareActionInCurrentTransaction(
            eq("action-owner"), eq("00000000-0000-0000-0000-000000000123"), any(), eq(null)))
        .thenThrow(programmerFailure);

    assertThatThrownBy(
            () ->
                service.prepare(
                    context(),
                    new PrepareActionCommand(
                        "REFUND_REQUEST", "00000000-0000-0000-0000-000000000123", 500L, "AUD")))
        .isSameAs(programmerFailure);
  }

  @Test
  void typedRefundIntegrityFailureRemainsAnAttributedDurableConflict() {
    executePrepareMutation();
    when(refunds.prepareActionInCurrentTransaction(
            eq("action-owner"), eq("00000000-0000-0000-0000-000000000123"), any(), eq(null)))
        .thenThrow(new RefundIntegrityException("controlled refund uniqueness corruption"));

    assertThatThrownBy(
            () ->
                service.prepare(
                    context(),
                    new PrepareActionCommand(
                        "REFUND_REQUEST", "00000000-0000-0000-0000-000000000123", 500L, "AUD")))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_DURABLE_TRUTH_INCONSISTENT);
            });
  }

  @Test
  void prepareCompetitionObservationUsesTheCompleteReplayResolver() {
    String order = "00000000-0000-0000-0000-000000000123";
    String attempt = "00000000-0000-0000-0000-000000000124";
    Instant createdAt = Instant.parse("2026-07-27T00:00:00Z");
    Instant expiresAt = createdAt.plus(Duration.ofMinutes(15));
    String argumentHash = ActionCanonical.hash("REFUND_REQUEST", order, "500", "AUD");
    String actionKey =
        ActionCanonical.hash(
            "action-owner",
            "support-session",
            "00000000-0000-0000-0000-000000000119",
            "REFUND_REQUEST",
            argumentHash);
    String pendingHash =
        ActionCanonical.hash(
            ACTION,
            actionKey,
            "REFUND_REQUEST",
            argumentHash,
            "action-owner",
            "support-session",
            "trace-118",
            "00000000-0000-0000-0000-000000000119",
            "refund:create",
            "",
            order,
            "STANDARD",
            attempt,
            "1",
            "500",
            "AUD",
            expiresAt.toString(),
            createdAt.toString());
    PendingActionRecord consumed =
        new PendingActionRecord(
            ACTION,
            actionKey,
            pendingHash,
            "REFUND_REQUEST",
            argumentHash,
            "action-owner",
            "support-session",
            "trace-118",
            "00000000-0000-0000-0000-000000000119",
            "refund:create",
            null,
            order,
            "STANDARD",
            attempt,
            1,
            500,
            "AUD",
            "CONSUMED",
            2,
            expiresAt,
            createdAt.plusSeconds(1),
            createdAt);
    doThrow(
            ActionRepository.ActionUniqueConflict.forPending(
                consumed, new DuplicateKeyException("controlled duplicate")))
        .when(transactions)
        .mutate(eq(ActionTransactions.Entry.PREPARE_INITIAL_MUTATION), any());
    when(transactions.maximumObservationAttempts()).thenReturn(1);
    when(transactions.observe(eq(ActionTransactions.Entry.PREPARE_TRUTH_OBSERVATION), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<PendingActionView> work = invocation.getArgument(1);
              return work.get();
            });
    when(repository.findPendingByTurnForUpdate(
            "action-owner", "support-session", "00000000-0000-0000-0000-000000000119"))
        .thenReturn(Optional.of(consumed));
    when(repository.findReceiptByPending(ACTION)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.prepare(
                    context(), new PrepareActionCommand("REFUND_REQUEST", order, 500L, "AUD")))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_DURABLE_TRUTH_INCONSISTENT);
            });
    verifyNoInteractions(refunds);
  }

  @Test
  void confirmedAbsenceAfterContentionIsAttributedIndeterminate() {
    CannotAcquireLockException lock = mysqlFailure(1205);
    when(transactions.maximumObservationAttempts()).thenReturn(1);
    doThrow(lock)
        .when(transactions)
        .mutate(eq(ActionTransactions.Entry.PREPARE_INITIAL_MUTATION), any());
    when(transactions.observe(eq(ActionTransactions.Entry.PREPARE_TRUTH_OBSERVATION), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<Optional<PendingActionView>> work = invocation.getArgument(1);
              return work.get();
            });
    when(repository.findPendingByTurnForUpdate(
            "action-owner", "support-session", "00000000-0000-0000-0000-000000000119"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.prepare(
                    context(),
                    new PrepareActionCommand(
                        "REFUND_REQUEST", "00000000-0000-0000-0000-000000000123", 500L, "AUD")))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(429);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_CONCURRENCY_OBSERVATION_INDETERMINATE);
            });
    verifyNoInteractions(refunds);
  }

  @Test
  void preparedWithoutReceiptAfterConfirmContentionIsAttributedIndeterminate() {
    CannotAcquireLockException lock = mysqlFailure(1213);
    when(transactions.maximumObservationAttempts()).thenReturn(1);
    doThrow(lock)
        .when(transactions)
        .mutate(eq(ActionTransactions.Entry.CONFIRM_INITIAL_MUTATION), any());
    when(transactions.observe(eq(ActionTransactions.Entry.CONFIRM_TRUTH_OBSERVATION), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<Optional<ActionReceiptView>> work = invocation.getArgument(1);
              return work.get();
            });
    PendingActionRecord pending = prepared();
    when(repository.findPendingByIdForUpdate(ACTION)).thenReturn(Optional.of(pending));
    when(repository.findReceiptByPending(ACTION)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.confirm(context(), ACTION))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(429);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_CONCURRENCY_OBSERVATION_INDETERMINATE);
            });
    verifyNoInteractions(refunds);
  }

  @Test
  void prepareDuplicateEnumeratesTheAlternativeActionKeyFace() {
    doThrow(
            ActionRepository.ActionUniqueConflict.forPending(
                prepared(), new DuplicateKeyException("controlled duplicate")))
        .when(transactions)
        .mutate(eq(ActionTransactions.Entry.PREPARE_INITIAL_MUTATION), any());
    when(transactions.maximumObservationAttempts()).thenReturn(1);
    when(transactions.observe(eq(ActionTransactions.Entry.PREPARE_TRUTH_OBSERVATION), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<Optional<PendingActionView>> work = invocation.getArgument(1);
              return work.get();
            });
    PendingActionRecord contradictory = mock(PendingActionRecord.class);
    when(repository.findPendingByActionKeyForUpdate(anyString()))
        .thenReturn(Optional.of(contradictory));

    assertThatThrownBy(
            () ->
                service.prepare(
                    context(),
                    new PrepareActionCommand(
                        "REFUND_REQUEST", "00000000-0000-0000-0000-000000000123", 500L, "AUD")))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_DURABLE_TRUTH_INCONSISTENT);
            });
    verifyNoInteractions(refunds);
  }

  @Test
  void confirmDuplicateEnumeratesTheAlternativeReceiptKeyFace() {
    ActionReceiptRecord attempted = mock(ActionReceiptRecord.class);
    when(attempted.receiptId()).thenReturn(UUID.randomUUID().toString());
    when(attempted.refundId()).thenReturn(UUID.randomUUID().toString());
    doThrow(
            ActionRepository.ActionUniqueConflict.forReceipt(
                attempted, new DuplicateKeyException("controlled duplicate")))
        .when(transactions)
        .mutate(eq(ActionTransactions.Entry.CONFIRM_INITIAL_MUTATION), any());
    when(transactions.maximumObservationAttempts()).thenReturn(1);
    when(transactions.observe(eq(ActionTransactions.Entry.CONFIRM_TRUTH_OBSERVATION), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<Optional<ActionReceiptView>> work = invocation.getArgument(1);
              return work.get();
            });
    PendingActionRecord pending = prepared();
    when(repository.findPendingByIdForUpdate(ACTION)).thenReturn(Optional.of(pending));
    ActionReceiptRecord contradictory = mock(ActionReceiptRecord.class);
    when(contradictory.receiptId()).thenReturn(UUID.randomUUID().toString());
    when(contradictory.pendingActionId()).thenReturn(UUID.randomUUID().toString());
    when(repository.findReceiptByActionKey(anyString())).thenReturn(Optional.of(contradictory));

    assertThatThrownBy(() -> service.confirm(context(), ACTION))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_DURABLE_TRUTH_INCONSISTENT);
            });
    verifyNoInteractions(refunds);
  }

  @Test
  void nestedRefundOrOutboxDuplicateIsDurableConflictNotIndeterminate() {
    doThrow(new DuplicateKeyException("controlled nested refund duplicate"))
        .when(transactions)
        .mutate(eq(ActionTransactions.Entry.CONFIRM_INITIAL_MUTATION), any());
    executeConfirmObservation();
    when(repository.findPendingByIdForUpdate(ACTION)).thenReturn(Optional.of(prepared()));
    when(repository.findReceiptByPending(ACTION)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.confirm(context(), ACTION))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_DURABLE_TRUTH_INCONSISTENT);
            });
    verifyNoInteractions(refunds);
  }

  @Test
  void prepareDuplicateEnumeratesTheAttemptedPendingIdFace() {
    PendingActionRecord attempted = prepared();
    doThrow(
            ActionRepository.ActionUniqueConflict.forPending(
                attempted, new DuplicateKeyException("controlled pending id duplicate")))
        .when(transactions)
        .mutate(eq(ActionTransactions.Entry.PREPARE_INITIAL_MUTATION), any());
    executePrepareObservation();
    PendingActionRecord contradictory = mock(PendingActionRecord.class);
    when(repository.findPendingByIdForUpdate(attempted.pendingActionId()))
        .thenReturn(Optional.of(contradictory));

    assertThatThrownBy(
            () ->
                service.prepare(
                    context(),
                    new PrepareActionCommand(
                        "REFUND_REQUEST", "00000000-0000-0000-0000-000000000123", 500L, "AUD")))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_DURABLE_TRUTH_INCONSISTENT);
            });
    verifyNoInteractions(refunds);
  }

  @Test
  void confirmDuplicateEnumeratesTheAttemptedReceiptIdFace() {
    String attemptedReceiptId = UUID.randomUUID().toString();
    String attemptedRefundId = UUID.randomUUID().toString();
    ActionReceiptRecord attempted = mock(ActionReceiptRecord.class);
    when(attempted.receiptId()).thenReturn(attemptedReceiptId);
    when(attempted.refundId()).thenReturn(attemptedRefundId);
    doThrow(
            ActionRepository.ActionUniqueConflict.forReceipt(
                attempted, new DuplicateKeyException("controlled receipt id duplicate")))
        .when(transactions)
        .mutate(eq(ActionTransactions.Entry.CONFIRM_INITIAL_MUTATION), any());
    executeConfirmObservation();
    when(repository.findPendingByIdForUpdate(ACTION)).thenReturn(Optional.of(prepared()));
    ActionReceiptRecord contradictory = contradictoryReceipt();
    when(repository.findReceiptById(attemptedReceiptId)).thenReturn(Optional.of(contradictory));

    assertThatThrownBy(() -> service.confirm(context(), ACTION))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_DURABLE_TRUTH_INCONSISTENT);
            });
    verifyNoInteractions(refunds);
  }

  @Test
  void confirmDuplicateEnumeratesTheAttemptedRefundIdFace() {
    String attemptedReceiptId = UUID.randomUUID().toString();
    String attemptedRefundId = UUID.randomUUID().toString();
    ActionReceiptRecord attempted = mock(ActionReceiptRecord.class);
    when(attempted.receiptId()).thenReturn(attemptedReceiptId);
    when(attempted.refundId()).thenReturn(attemptedRefundId);
    doThrow(
            ActionRepository.ActionUniqueConflict.forReceipt(
                attempted, new DuplicateKeyException("controlled refund id duplicate")))
        .when(transactions)
        .mutate(eq(ActionTransactions.Entry.CONFIRM_INITIAL_MUTATION), any());
    executeConfirmObservation();
    when(repository.findPendingByIdForUpdate(ACTION)).thenReturn(Optional.of(prepared()));
    ActionReceiptRecord contradictory = contradictoryReceipt();
    when(repository.findReceiptByRefund(attemptedRefundId)).thenReturn(Optional.of(contradictory));

    assertThatThrownBy(() -> service.confirm(context(), ACTION))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_DURABLE_TRUTH_INCONSISTENT);
            });
    verifyNoInteractions(refunds);
  }

  private void executePrepareObservation() {
    when(transactions.maximumObservationAttempts()).thenReturn(1);
    when(transactions.observe(eq(ActionTransactions.Entry.PREPARE_TRUTH_OBSERVATION), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<Optional<PendingActionView>> work = invocation.getArgument(1);
              return work.get();
            });
  }

  private void executePrepareMutation() {
    when(transactions.mutate(eq(ActionTransactions.Entry.PREPARE_INITIAL_MUTATION), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<PendingActionView> work = invocation.getArgument(1);
              return work.get();
            });
  }

  private void executeConfirmObservation() {
    when(transactions.maximumObservationAttempts()).thenReturn(1);
    when(transactions.observe(eq(ActionTransactions.Entry.CONFIRM_TRUTH_OBSERVATION), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<Optional<ActionReceiptView>> work = invocation.getArgument(1);
              return work.get();
            });
  }

  private static ActionReceiptRecord contradictoryReceipt() {
    ActionReceiptRecord contradictory = mock(ActionReceiptRecord.class);
    when(contradictory.receiptId()).thenReturn(UUID.randomUUID().toString());
    when(contradictory.pendingActionId()).thenReturn(UUID.randomUUID().toString());
    return contradictory;
  }

  private static ActionRequestContext context() {
    return new ActionRequestContext(
        "action-owner",
        "support-session",
        "trace-118",
        "00000000-0000-0000-0000-000000000119",
        null,
        "refund:create");
  }

  private static PendingActionRecord prepared() {
    Instant createdAt = Instant.parse("2026-07-27T00:00:00Z");
    Instant expiresAt = createdAt.plus(Duration.ofMinutes(15));
    String order = "00000000-0000-0000-0000-000000000123";
    String argumentHash = ActionCanonical.hash("REFUND_REQUEST", order, "500", "AUD");
    String actionKey =
        ActionCanonical.hash(
            "action-owner",
            "support-session",
            "00000000-0000-0000-0000-000000000119",
            "REFUND_REQUEST",
            argumentHash);
    return new PendingActionRecord(
        ACTION,
        actionKey,
        ActionCanonical.hash(
            ACTION,
            actionKey,
            "REFUND_REQUEST",
            argumentHash,
            "action-owner",
            "support-session",
            "trace-118",
            "00000000-0000-0000-0000-000000000119",
            "refund:create",
            "",
            order,
            "STANDARD",
            "00000000-0000-0000-0000-000000000124",
            "1",
            "500",
            "AUD",
            expiresAt.toString(),
            createdAt.toString()),
        "REFUND_REQUEST",
        argumentHash,
        "action-owner",
        "support-session",
        "trace-118",
        "00000000-0000-0000-0000-000000000119",
        "refund:create",
        null,
        order,
        "STANDARD",
        "00000000-0000-0000-0000-000000000124",
        1,
        500,
        "AUD",
        "PREPARED",
        1,
        expiresAt,
        null,
        createdAt);
  }

  private static CannotAcquireLockException mysqlFailure(int vendorCode) {
    return new CannotAcquireLockException(
        "controlled MySQL contention",
        new SQLException("controlled MySQL contention", "40001", vendorCode));
  }
}
