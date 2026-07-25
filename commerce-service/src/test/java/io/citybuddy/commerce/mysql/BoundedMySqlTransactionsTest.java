package io.citybuddy.commerce.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class BoundedMySqlTransactionsTest {
  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void workUsesOneActiveSessionAndRestoresItsPriorPolicy() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TransactionTemplate template = activeTransactionTemplate();
    Runnable work = mock(Runnable.class);
    when(jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Long.class))
        .thenReturn(50L);
    BoundedMySqlTransactions transactions = new BoundedMySqlTransactions(jdbc, template, 2);

    assertThat(
            transactions.execute(
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
    BoundedMySqlTransactions transactions = new BoundedMySqlTransactions(jdbc, template, 1);

    assertThatThrownBy(() -> transactions.execute(() -> "observed"))
        .isInstanceOf(DataAccessResourceFailureException.class)
        .hasMessageContaining("restore");
  }

  @Test
  void inactiveTransactionCannotChangeSessionPolicy() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TransactionTemplate template = mock(TransactionTemplate.class);
    when(template.execute(any()))
        .thenAnswer(
            invocation ->
                invocation
                    .<TransactionCallback<?>>getArgument(0)
                    .doInTransaction(mock(TransactionStatus.class)));
    BoundedMySqlTransactions transactions = new BoundedMySqlTransactions(jdbc, template, 1);

    assertThatThrownBy(() -> transactions.execute(() -> "observed"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active transaction");
    verifyNoInteractions(jdbc);
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
