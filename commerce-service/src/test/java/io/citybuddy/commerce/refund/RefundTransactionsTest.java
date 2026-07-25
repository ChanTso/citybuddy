package io.citybuddy.commerce.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.citybuddy.commerce.mysql.BoundedMySqlTransactions;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;

class RefundTransactionsTest {
  @Test
  void entryInventoryIsClosedAndRuntimeModeChecksAreEnforced() {
    RefundTransactions transactions =
        new RefundTransactions(mock(BoundedMySqlTransactions.class), 2, Duration.ZERO);

    assertThat(RefundTransactions.Entry.requiredInventory())
        .isEqualTo(Set.of(RefundTransactions.Entry.values()))
        .containsExactlyInAnyOrder(
            RefundTransactions.Entry.DIRECT_INITIAL_MUTATION,
            RefundTransactions.Entry.DIRECT_TRUTH_OBSERVATION,
            RefundTransactions.Entry.DIRECT_FINAL_MUTATION,
            RefundTransactions.Entry.DIRECT_FINAL_OBSERVATION,
            RefundTransactions.Entry.MARK_PROCESSING_MUTATION,
            RefundTransactions.Entry.MARK_PROCESSING_OBSERVATION,
            RefundTransactions.Entry.SUCCEED_MUTATION,
            RefundTransactions.Entry.SUCCEED_OBSERVATION,
            RefundTransactions.Entry.FAIL_MUTATION,
            RefundTransactions.Entry.FAIL_OBSERVATION,
            RefundTransactions.Entry.RECONCILE_MUTATION,
            RefundTransactions.Entry.RECONCILE_OBSERVATION);

    assertThatThrownBy(
            () ->
                transactions.observe(
                    RefundTransactions.Entry.DIRECT_INITIAL_MUTATION, () -> "wrong mode"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                transactions.mutate(
                    RefundTransactions.Entry.DIRECT_TRUTH_OBSERVATION, () -> "wrong mode"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onlyMysql1205And1213AnywhereInTheCauseChainAreContention() {
    assertThat(RefundTransactions.isMySqlContention(mysqlLockFailure(1205))).isTrue();
    assertThat(RefundTransactions.isMySqlContention(mysqlLockFailure(1213))).isTrue();
    assertThat(RefundTransactions.isMySqlContention(mysqlLockFailure(1040))).isFalse();
    assertThat(
            RefundTransactions.isMySqlContention(
                new DataAccessResourceFailureException("resource unavailable")))
        .isFalse();
  }

  private static CannotAcquireLockException mysqlLockFailure(int vendorCode) {
    return new CannotAcquireLockException(
        "controlled MySQL failure",
        new SQLException("controlled MySQL failure", "40001", vendorCode));
  }
}
