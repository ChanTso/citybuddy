package io.citybuddy.commerce.action;

import io.citybuddy.commerce.mysql.BoundedMySqlTransactions;
import io.citybuddy.commerce.mysql.MySqlSessionPolicyRestorationException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

final class ActionTransactions {
  private final BoundedMySqlTransactions transactions;
  private final int maximumObservationAttempts;
  private final Duration observationBackoff;

  ActionTransactions(
      BoundedMySqlTransactions transactions,
      int maximumObservationAttempts,
      Duration observationBackoff) {
    this.transactions = transactions;
    this.maximumObservationAttempts = maximumObservationAttempts;
    this.observationBackoff = observationBackoff;
  }

  <T> T mutate(Entry entry, Supplier<T> work) {
    entry.require(Mode.MUTATION);
    return transactions.execute(work);
  }

  <T> T observe(Entry entry, Supplier<T> work) {
    entry.require(Mode.OBSERVATION);
    return transactions.execute(work);
  }

  int maximumObservationAttempts() {
    return maximumObservationAttempts;
  }

  boolean pause(int attempt) {
    try {
      Thread.sleep(observationBackoff.multipliedBy(attempt).toMillis());
      return true;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  static boolean isMySqlContention(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof MySqlSessionPolicyRestorationException) {
        return false;
      }
      current = current.getCause();
    }
    current = failure;
    while (current != null) {
      if (current instanceof SQLException sql
          && (sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  enum Entry {
    PREPARE_INITIAL_MUTATION(Mode.MUTATION),
    PREPARE_TRUTH_OBSERVATION(Mode.OBSERVATION),
    CONFIRM_INITIAL_MUTATION(Mode.MUTATION),
    CONFIRM_TRUTH_OBSERVATION(Mode.OBSERVATION);

    private final Mode mode;

    Entry(Mode mode) {
      this.mode = mode;
    }

    void require(Mode expected) {
      if (mode != expected) {
        throw new IllegalArgumentException("Action transaction entry used in the wrong mode");
      }
    }

    static Set<Entry> requiredInventory() {
      return Set.of(values());
    }
  }

  private enum Mode {
    MUTATION,
    OBSERVATION
  }
}
