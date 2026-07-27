package io.citybuddy.commerce.mysql;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one transaction with a physical MySQL row-lock wait bound on its transaction-bound session.
 */
public final class BoundedMySqlTransactions {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final int lockWaitTimeoutSeconds;

  public BoundedMySqlTransactions(
      JdbcTemplate jdbc, TransactionTemplate transactions, int lockWaitTimeoutSeconds) {
    if (lockWaitTimeoutSeconds < 1 || lockWaitTimeoutSeconds > 60) {
      throw new IllegalArgumentException("MySQL lock wait timeout must be between 1 and 60");
    }
    this.jdbc = Objects.requireNonNull(jdbc, "JDBC template is required");
    this.transactions = Objects.requireNonNull(transactions, "Transaction template is required");
    this.lockWaitTimeoutSeconds = lockWaitTimeoutSeconds;
  }

  public <T> T execute(Supplier<T> work) {
    Objects.requireNonNull(work, "Bounded MySQL work is required");
    T result =
        transactions.execute(
            status -> {
              if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException(
                    "Bounded MySQL work requires an active transaction");
              }
              Long previous =
                  jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Long.class);
              if (previous == null) {
                throw new IllegalStateException("MySQL lock wait timeout is unavailable");
              }
              jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + lockWaitTimeoutSeconds);
              try {
                return work.get();
              } finally {
                // A failed restore must abort the transaction instead of returning a pooled session
                // whose policy no longer matches the pool's prior state.
                try {
                  jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + previous);
                } catch (RuntimeException restorationFailure) {
                  throw new MySqlSessionPolicyRestorationException(restorationFailure);
                }
              }
            });
    if (result == null) {
      throw new IllegalStateException("Bounded MySQL transaction returned no result");
    }
    return result;
  }

  public int lockWaitTimeoutSeconds() {
    return lockWaitTimeoutSeconds;
  }
}
