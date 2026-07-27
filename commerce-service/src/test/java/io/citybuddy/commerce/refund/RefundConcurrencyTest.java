package io.citybuddy.commerce.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.citybuddy.commerce.mysql.MySqlSessionPolicyRestorationException;
import io.citybuddy.commerce.payment.MockPaymentRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;

class RefundConcurrencyTest {
  private static final String USER = "refund-owner";
  private static final String ORDER_ID = "00000000-0000-0000-0000-000000000120";
  private static final RefundRequest REQUEST = new RefundRequest(100L, "AUD", null);

  private final RefundRepository refunds = mock(RefundRepository.class);
  private final MockPaymentRepository payments = mock(MockPaymentRepository.class);
  private final RefundTransactions transactions = mock(RefundTransactions.class);
  private final RefundService service =
      new RefundService(refunds, payments, transactions, Clock.systemUTC());

  @Test
  void repeatedMysql1205ObservationIsIndeterminateWithoutMutationOrExceptionEscape() {
    CannotAcquireLockException lock = mysqlFailure(1205);
    when(transactions.maximumObservationAttempts()).thenReturn(2);
    when(transactions.pause(1)).thenReturn(true);
    doThrow(lock)
        .when(transactions)
        .mutate(eq(RefundTransactions.Entry.DIRECT_INITIAL_MUTATION), any());
    doThrow(lock)
        .when(transactions)
        .observe(eq(RefundTransactions.Entry.DIRECT_TRUTH_OBSERVATION), any());

    assertThatThrownBy(() -> service.request(USER, ORDER_ID, "refund-lock-timeout", REQUEST))
        .isInstanceOfSatisfying(
            RefundException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(429);
              assertThat(exception.category()).isEqualTo("INDETERMINATE");
              assertThat(exception.reason())
                  .isEqualTo(RefundRejectionReason.REFUND_CONCURRENCY_OBSERVATION_INDETERMINATE);
            });
    verifyNoInteractions(refunds, payments);
  }

  @Test
  void repeatedMysql1213ObservationUsesTheSameBoundedClassification() {
    CannotAcquireLockException deadlock = mysqlFailure(1213);
    when(transactions.maximumObservationAttempts()).thenReturn(1);
    doThrow(deadlock)
        .when(transactions)
        .mutate(eq(RefundTransactions.Entry.DIRECT_INITIAL_MUTATION), any());
    doThrow(deadlock)
        .when(transactions)
        .observe(eq(RefundTransactions.Entry.DIRECT_TRUTH_OBSERVATION), any());

    assertThatThrownBy(() -> service.request(USER, ORDER_ID, "refund-deadlock", REQUEST))
        .isInstanceOfSatisfying(
            RefundException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(RefundRejectionReason.REFUND_CONCURRENCY_OBSERVATION_INDETERMINATE));
    verifyNoInteractions(refunds, payments);
  }

  @Test
  void dependencyFailureAndNonMysqlLockFailureAreNotReclassifiedAsContention() {
    DataAccessResourceFailureException unavailable =
        new DataAccessResourceFailureException("controlled unavailable");
    doThrow(unavailable)
        .when(transactions)
        .mutate(eq(RefundTransactions.Entry.DIRECT_INITIAL_MUTATION), any());

    assertThatThrownBy(() -> service.request(USER, ORDER_ID, "refund-unavailable", REQUEST))
        .isSameAs(unavailable);

    CannotAcquireLockException unclassified =
        new CannotAcquireLockException("no MySQL vendor cause");
    doThrow(unclassified)
        .when(transactions)
        .mutate(eq(RefundTransactions.Entry.DIRECT_INITIAL_MUTATION), any());
    assertThatThrownBy(() -> service.request(USER, ORDER_ID, "refund-unclassified", REQUEST))
        .isSameAs(unclassified);
  }

  @Test
  void sessionPolicyRestorationFailureNeverEntersContentionRecovery() {
    MySqlSessionPolicyRestorationException restoration =
        new MySqlSessionPolicyRestorationException(mysqlFailure(1205));
    doThrow(restoration)
        .when(transactions)
        .mutate(eq(RefundTransactions.Entry.DIRECT_INITIAL_MUTATION), any());

    assertThatThrownBy(() -> service.request(USER, ORDER_ID, "refund-restore-failure", REQUEST))
        .isSameAs(restoration);
    verify(transactions, never())
        .observe(eq(RefundTransactions.Entry.DIRECT_TRUTH_OBSERVATION), any());
    verify(transactions, never()).mutate(eq(RefundTransactions.Entry.DIRECT_FINAL_MUTATION), any());
    verifyNoInteractions(refunds, payments);
  }

  @Test
  void lifecycleAndReconciliationReturnTypedIndeterminateInsteadOfBusinessOutcome() {
    String refundId = UUID.randomUUID().toString();
    CannotAcquireLockException lock = mysqlFailure(1205);
    when(transactions.maximumObservationAttempts()).thenReturn(1);
    doThrow(lock)
        .when(transactions)
        .mutate(eq(RefundTransactions.Entry.MARK_PROCESSING_MUTATION), any());
    doThrow(lock)
        .when(transactions)
        .observe(eq(RefundTransactions.Entry.MARK_PROCESSING_OBSERVATION), any());

    assertThatThrownBy(() -> service.markProcessing(refundId))
        .isInstanceOfSatisfying(
            RefundIndeterminateException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(RefundRejectionReason.REFUND_CONCURRENCY_OBSERVATION_INDETERMINATE));

    doThrow(lock).when(transactions).mutate(eq(RefundTransactions.Entry.RECONCILE_MUTATION), any());
    doThrow(lock)
        .when(transactions)
        .observe(eq(RefundTransactions.Entry.RECONCILE_OBSERVATION), any());
    assertThatThrownBy(() -> service.reconcile(refundId))
        .isInstanceOfSatisfying(
            RefundIndeterminateException.class,
            exception ->
                assertThat(exception.reason())
                    .isEqualTo(RefundRejectionReason.REFUND_CONCURRENCY_OBSERVATION_INDETERMINATE));
  }

  private static CannotAcquireLockException mysqlFailure(int vendorCode) {
    return new CannotAcquireLockException(
        "controlled MySQL contention",
        new SQLException("controlled MySQL contention", "40001", vendorCode));
  }
}
