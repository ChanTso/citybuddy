package io.citybuddy.commerce.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.citybuddy.commerce.mysql.BoundedMySqlTransactions;
import io.citybuddy.commerce.mysql.MySqlSessionPolicyRestorationException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;

class ActionTransactionsTest {
  @Test
  void transactionEntryInventoryIsClosedAndModesAreEnforced() {
    ActionTransactions transactions =
        new ActionTransactions(mock(BoundedMySqlTransactions.class), 2, Duration.ofMillis(1));

    assertThat(ActionTransactions.Entry.requiredInventory())
        .isEqualTo(Set.of(ActionTransactions.Entry.values()))
        .containsExactlyInAnyOrder(
            ActionTransactions.Entry.PREPARE_INITIAL_MUTATION,
            ActionTransactions.Entry.PREPARE_TRUTH_OBSERVATION,
            ActionTransactions.Entry.CONFIRM_INITIAL_MUTATION,
            ActionTransactions.Entry.CONFIRM_TRUTH_OBSERVATION);
    assertThatThrownBy(
            () ->
                transactions.observe(
                    ActionTransactions.Entry.PREPARE_INITIAL_MUTATION, () -> "wrong"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                transactions.mutate(
                    ActionTransactions.Entry.CONFIRM_TRUTH_OBSERVATION, () -> "wrong"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onlyMysql1205And1213AreContentionAndRestorationWinsAttribution() {
    assertThat(ActionTransactions.isMySqlContention(mysqlFailure(1205))).isTrue();
    assertThat(ActionTransactions.isMySqlContention(mysqlFailure(1213))).isTrue();
    assertThat(ActionTransactions.isMySqlContention(mysqlFailure(1040))).isFalse();
    assertThat(
            ActionTransactions.isMySqlContention(
                new DataAccessResourceFailureException("unavailable")))
        .isFalse();
    assertThat(
            ActionTransactions.isMySqlContention(
                new MySqlSessionPolicyRestorationException(mysqlFailure(1205))))
        .isFalse();
  }

  private static CannotAcquireLockException mysqlFailure(int vendorCode) {
    return new CannotAcquireLockException(
        "controlled MySQL failure",
        new SQLException("controlled MySQL failure", "40001", vendorCode));
  }
}
