package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class MockPaymentTransactionsTest {
  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void everyRegisteredEntryUsesOneActiveSessionAndRestoresItsPriorPolicy() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TransactionTemplate template = activeTransactionTemplate();
    Runnable work = mock(Runnable.class);
    when(jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Long.class))
        .thenReturn(50L);
    MockPaymentTransactions transactions = new MockPaymentTransactions(jdbc, template, 2);

    assertThat(
            transactions.mutate(
                MockPaymentTransactions.Entry.START_INITIAL_MUTATION,
                () -> {
                  work.run();
                  return "mutation";
                }))
        .isEqualTo("mutation");

    InOrder ordered = inOrder(jdbc, work);
    ordered.verify(jdbc).queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Long.class);
    ordered.verify(jdbc).execute("SET SESSION innodb_lock_wait_timeout = 2");
    ordered.verify(work).run();
    ordered.verify(jdbc).execute("SET SESSION innodb_lock_wait_timeout = 50");
  }

  @Test
  void restorationFailureIsVisibleAndCannotReturnAContaminatedSuccess() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TransactionTemplate template = activeTransactionTemplate();
    when(jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Long.class))
        .thenReturn(50L);
    org.mockito.Mockito.doNothing()
        .doThrow(new DataAccessResourceFailureException("controlled restore failure"))
        .when(jdbc)
        .execute(any(String.class));
    MockPaymentTransactions transactions = new MockPaymentTransactions(jdbc, template, 1);

    assertThatThrownBy(
            () ->
                transactions.observe(
                    MockPaymentTransactions.Entry.START_TRUTH_OBSERVATION, () -> "observed"))
        .isInstanceOf(DataAccessResourceFailureException.class)
        .hasMessageContaining("restore");
  }

  @Test
  void anInactiveTransactionCannotChangeSessionPolicy() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TransactionTemplate template = mock(TransactionTemplate.class);
    when(template.execute(any()))
        .thenAnswer(
            invocation ->
                invocation
                    .<TransactionCallback<?>>getArgument(0)
                    .doInTransaction(mock(TransactionStatus.class)));
    MockPaymentTransactions transactions = new MockPaymentTransactions(jdbc, template, 1);

    assertThatThrownBy(
            () ->
                transactions.observe(
                    MockPaymentTransactions.Entry.CALLBACK_TRUTH_OBSERVATION, () -> "observed"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active transaction");
    verifyNoInteractions(jdbc);
  }

  @Test
  void entryInventoryIsClosedAndCarriesModeLockOrderAndWritePolicy() {
    assertThat(MockPaymentTransactions.Entry.requiredInventory())
        .isEqualTo(Set.of(MockPaymentTransactions.Entry.values()))
        .containsExactlyInAnyOrder(
            MockPaymentTransactions.Entry.START_INITIAL_MUTATION,
            MockPaymentTransactions.Entry.START_TRUTH_OBSERVATION,
            MockPaymentTransactions.Entry.START_FINAL_MUTATION,
            MockPaymentTransactions.Entry.START_FINAL_OBSERVATION,
            MockPaymentTransactions.Entry.CALLBACK_INITIAL_MUTATION,
            MockPaymentTransactions.Entry.CALLBACK_TRUTH_OBSERVATION);
    assertThat(
            MockPaymentTransactions.Entry.requiredInventory().stream()
                .filter(MockPaymentTransactions.Entry::writesAllowed)
                .map(MockPaymentTransactions.Entry::mode))
        .containsOnly(MockPaymentTransactions.Mode.MUTATION);
    assertThat(
            MockPaymentTransactions.Entry.requiredInventory().stream()
                .map(MockPaymentTransactions.Entry::lockOrder))
        .allMatch(order -> order.startsWith("attempt"));
    assertThat(
            MockPaymentTransactions.Entry.requiredInventory().stream()
                .map(MockPaymentTransactions.Entry::phase))
        .doesNotContain("");
    assertThat(
            MockPaymentTransactions.Entry.requiredInventory().stream()
                .filter(entry -> entry.mode() == MockPaymentTransactions.Mode.OBSERVATION)
                .map(MockPaymentTransactions.Entry::observationOutcomes))
        .allMatch(
            outcomes ->
                outcomes.equals(Set.of(MockPaymentTransactions.ObservationOutcome.values())));
    assertThat(
            MockPaymentTransactions.Entry.requiredInventory().stream()
                .filter(entry -> entry.mode() == MockPaymentTransactions.Mode.MUTATION)
                .map(MockPaymentTransactions.Entry::observationOutcomes))
        .allMatch(Set::isEmpty);
    assertThat(
            MockPaymentTransactions.Entry.requiredInventory().stream()
                .map(MockPaymentTransactions.Entry::allowedFailureRoots))
        .allMatch(
            roots ->
                roots.contains(MockPaymentTransactions.FailureRoot.MYSQL_1205_OR_1213)
                    && roots.contains(
                        MockPaymentTransactions.FailureRoot.DEPENDENCY_RESOURCE_UNAVAILABLE)
                    && roots.contains(
                        MockPaymentTransactions.FailureRoot
                            .UNEXPECTED_PROGRAMMER_OR_CONFIGURATION));
    assertThat(
            MockPaymentTransactions.Entry.requiredInventory().stream()
                .map(MockPaymentTransactions.Entry::externalClassifications))
        .allMatch(
            classifications ->
                classifications.contains(
                        MockPaymentTransactions.ExternalClassification.INDETERMINATE_429)
                    && classifications.contains(
                        MockPaymentTransactions.ExternalClassification.DEPENDENCY_UNAVAILABLE_503)
                    && classifications.contains(
                        MockPaymentTransactions.ExternalClassification
                            .DURABLE_OR_INTENT_CONFLICT_409));
  }

  private static TransactionTemplate activeTransactionTemplate() {
    TransactionTemplate template = mock(TransactionTemplate.class);
    when(template.execute(any()))
        .thenAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              try {
                return invocation
                    .<TransactionCallback<?>>getArgument(0)
                    .doInTransaction(mock(TransactionStatus.class));
              } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
              }
            });
    return template;
  }
}
