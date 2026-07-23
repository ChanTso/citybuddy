package io.citybuddy.commerce.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.citybuddy.commerce.evaluation.EvaluationSandboxAccess;
import io.citybuddy.commerce.refund.RefundService;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ActionServiceTest {
  private static final String ACTION_ID = "00000000-0000-0000-0000-000000000120";
  private ActionRepository repository;
  private PlatformTransactionManager transactionManager;
  private ActionService service;

  @BeforeEach
  void setUp() {
    repository = mock(ActionRepository.class);
    transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    when(repository.withLockWaitTimeout(eq(1), any()))
        .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
    @SuppressWarnings("unchecked")
    ObjectProvider<EvaluationSandboxAccess> sandboxAccess = mock(ObjectProvider.class);
    service =
        new ActionService(
            repository,
            mock(RefundService.class),
            new TransactionTemplate(transactionManager),
            new ActionProperties("refund:create", Duration.ofMinutes(15), 2, 1),
            Clock.systemUTC(),
            sandboxAccess);
  }

  @Test
  void lockObservationFailureRemainsIndeterminateRatherThanAbsenceOrUnavailability() {
    when(repository.findPendingByIdForUpdate(ACTION_ID))
        .thenThrow(new PessimisticLockingFailureException("controlled lock contention"));

    assertThatThrownBy(() -> service.confirm(context(), ACTION_ID))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(429);
              assertThat(exception.category()).isEqualTo("INDETERMINATE");
            });

    verify(repository, times(8)).findPendingByIdForUpdate(ACTION_ID);
    verify(repository, times(8)).withLockWaitTimeout(eq(1), any());
  }

  @Test
  void trueDatabaseResourceFailureRemainsUnavailableAndIsNotRetried() {
    when(repository.findPendingByIdForUpdate(ACTION_ID))
        .thenThrow(new DataAccessResourceFailureException("controlled database outage"));

    assertThatThrownBy(() -> service.confirm(context(), ACTION_ID))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(503);
              assertThat(exception.category()).isEqualTo("DEPENDENCY_UNAVAILABLE");
            });

    verify(repository).findPendingByIdForUpdate(ACTION_ID);
    verify(repository).withLockWaitTimeout(eq(1), any());
  }

  private static ActionRequestContext context() {
    return new ActionRequestContext(
        "user-1",
        "session-1",
        "trace-1",
        "00000000-0000-0000-0000-000000000121",
        "sandbox-payment",
        "refund:create");
  }
}
