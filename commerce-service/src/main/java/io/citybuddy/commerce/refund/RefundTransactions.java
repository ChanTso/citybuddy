package io.citybuddy.commerce.refund;

import io.citybuddy.commerce.mysql.BoundedMySqlTransactions;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

final class RefundTransactions {
  private final BoundedMySqlTransactions transactions;
  private final int maximumObservationAttempts;
  private final Duration observationBackoff;

  RefundTransactions(
      BoundedMySqlTransactions transactions,
      int maximumObservationAttempts,
      Duration observationBackoff) {
    this.transactions = transactions;
    this.maximumObservationAttempts = maximumObservationAttempts;
    this.observationBackoff = observationBackoff;
  }

  <T> T mutate(Entry entry, Supplier<T> work) {
    requireMode(entry, Mode.MUTATION);
    return transactions.execute(work);
  }

  <T> T observe(Entry entry, Supplier<T> work) {
    requireMode(entry, Mode.OBSERVATION);
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
      if (current instanceof SQLException sql
          && (sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static void requireMode(Entry entry, Mode expected) {
    if (entry.mode() != expected) {
      throw new IllegalArgumentException("Refund transaction entry has the wrong mode");
    }
  }

  enum Mode {
    MUTATION,
    OBSERVATION
  }

  enum Entry {
    DIRECT_INITIAL_MUTATION(Mode.MUTATION),
    DIRECT_TRUTH_OBSERVATION(Mode.OBSERVATION),
    DIRECT_FINAL_MUTATION(Mode.MUTATION),
    DIRECT_FINAL_OBSERVATION(Mode.OBSERVATION),
    MARK_PROCESSING_MUTATION(Mode.MUTATION),
    MARK_PROCESSING_OBSERVATION(Mode.OBSERVATION),
    SUCCEED_MUTATION(Mode.MUTATION),
    SUCCEED_OBSERVATION(Mode.OBSERVATION),
    FAIL_MUTATION(Mode.MUTATION),
    FAIL_OBSERVATION(Mode.OBSERVATION),
    RECONCILE_MUTATION(Mode.MUTATION),
    RECONCILE_OBSERVATION(Mode.OBSERVATION);

    private final Mode mode;

    Entry(Mode mode) {
      this.mode = mode;
    }

    Mode mode() {
      return mode;
    }

    static Set<Entry> requiredInventory() {
      return Set.of(values());
    }
  }
}
