package io.citybuddy.commerce.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.citybuddy.commerce.action.ActionRepository.PendingActionRecord;
import io.citybuddy.commerce.evaluation.EvaluationSandboxAccess;
import io.citybuddy.commerce.refund.RefundService;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
    doThrow(new DuplicateKeyException("controlled duplicate"))
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

  private static ActionRequestContext context() {
    return new ActionRequestContext(
        "action-owner",
        "support-session",
        "trace-118",
        "00000000-0000-0000-0000-000000000119",
        null,
        "refund:create");
  }

  private static CannotAcquireLockException mysqlFailure(int vendorCode) {
    return new CannotAcquireLockException(
        "controlled MySQL contention",
        new SQLException("controlled MySQL contention", "40001", vendorCode));
  }
}
