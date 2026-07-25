package io.citybuddy.commerce.payment;

import java.util.Set;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

final class MockPaymentTransactions {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final int lockWaitTimeoutSeconds;

  MockPaymentTransactions(
      JdbcTemplate jdbc, TransactionTemplate transactions, int lockWaitTimeoutSeconds) {
    if (lockWaitTimeoutSeconds < 1 || lockWaitTimeoutSeconds > 60) {
      throw new IllegalArgumentException("Payment lock wait timeout must be between 1 and 60");
    }
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.lockWaitTimeoutSeconds = lockWaitTimeoutSeconds;
  }

  <T> T mutate(Entry entry, Supplier<T> work) {
    if (entry.mode() != Mode.MUTATION) {
      throw new IllegalArgumentException("Payment transaction entry is not a mutation");
    }
    return execute(work);
  }

  <T> T observe(Entry entry, Supplier<T> work) {
    if (entry.mode() != Mode.OBSERVATION) {
      throw new IllegalArgumentException("Payment transaction entry is not an observation");
    }
    return execute(work);
  }

  private <T> T execute(Supplier<T> work) {
    return transactions.execute(
        status -> {
          if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "Payment lock wait boundary requires an active transaction");
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
            jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + previous);
          }
        });
  }

  enum Mode {
    MUTATION,
    OBSERVATION
  }

  enum FailureRoot {
    DUPLICATE_KEY,
    MYSQL_1205_OR_1213,
    DEPENDENCY_RESOURCE_UNAVAILABLE,
    UNEXPECTED_PROGRAMMER_OR_CONFIGURATION
  }

  enum ObservationOutcome {
    FOUND,
    CONFIRMED_ABSENT,
    INDETERMINATE
  }

  enum ExternalClassification {
    SUCCESS_200_OR_201,
    CONCEALMENT_404,
    DURABLE_OR_INTENT_CONFLICT_409,
    INDETERMINATE_429,
    DEPENDENCY_UNAVAILABLE_503,
    SANDBOX_REJECTION_403,
    UNEXPECTED_FAILURE_500
  }

  enum Entry {
    START_INITIAL_MUTATION(
        Mode.MUTATION,
        "attempt-command -> order -> sandbox",
        true,
        "initial",
        mutationFailures(),
        Set.of(),
        startClassifications()),
    START_TRUTH_OBSERVATION(
        Mode.OBSERVATION,
        "attempt-command -> order -> sandbox",
        false,
        "bounded",
        observationFailures(),
        allObservationOutcomes(),
        startClassifications()),
    START_FINAL_MUTATION(
        Mode.MUTATION,
        "attempt-command -> order -> sandbox",
        true,
        "confirmed-absence-only",
        mutationFailures(),
        Set.of(),
        startClassifications()),
    START_FINAL_OBSERVATION(
        Mode.OBSERVATION,
        "attempt-command -> order -> sandbox",
        false,
        "single-final",
        observationFailures(),
        allObservationOutcomes(),
        startClassifications()),
    CALLBACK_INITIAL_MUTATION(
        Mode.MUTATION,
        "attempt -> order -> sandbox",
        true,
        "initial",
        mutationFailures(),
        Set.of(),
        callbackClassifications()),
    CALLBACK_TRUTH_OBSERVATION(
        Mode.OBSERVATION,
        "attempt -> order -> sandbox",
        false,
        "bounded",
        observationFailures(),
        allObservationOutcomes(),
        callbackClassifications());

    private final Mode mode;
    private final String lockOrder;
    private final boolean writesAllowed;
    private final String phase;
    private final Set<FailureRoot> allowedFailureRoots;
    private final Set<ObservationOutcome> observationOutcomes;
    private final Set<ExternalClassification> externalClassifications;

    Entry(
        Mode mode,
        String lockOrder,
        boolean writesAllowed,
        String phase,
        Set<FailureRoot> allowedFailureRoots,
        Set<ObservationOutcome> observationOutcomes,
        Set<ExternalClassification> externalClassifications) {
      this.mode = mode;
      this.lockOrder = lockOrder;
      this.writesAllowed = writesAllowed;
      this.phase = phase;
      this.allowedFailureRoots = Set.copyOf(allowedFailureRoots);
      this.observationOutcomes = Set.copyOf(observationOutcomes);
      this.externalClassifications = Set.copyOf(externalClassifications);
    }

    Mode mode() {
      return mode;
    }

    String lockOrder() {
      return lockOrder;
    }

    boolean writesAllowed() {
      return writesAllowed;
    }

    String phase() {
      return phase;
    }

    Set<FailureRoot> allowedFailureRoots() {
      return allowedFailureRoots;
    }

    Set<ObservationOutcome> observationOutcomes() {
      return observationOutcomes;
    }

    Set<ExternalClassification> externalClassifications() {
      return externalClassifications;
    }

    static Set<Entry> requiredInventory() {
      return Set.of(values());
    }

    private static Set<FailureRoot> mutationFailures() {
      return Set.of(
          FailureRoot.DUPLICATE_KEY,
          FailureRoot.MYSQL_1205_OR_1213,
          FailureRoot.DEPENDENCY_RESOURCE_UNAVAILABLE,
          FailureRoot.UNEXPECTED_PROGRAMMER_OR_CONFIGURATION);
    }

    private static Set<FailureRoot> observationFailures() {
      return Set.of(
          FailureRoot.MYSQL_1205_OR_1213,
          FailureRoot.DEPENDENCY_RESOURCE_UNAVAILABLE,
          FailureRoot.UNEXPECTED_PROGRAMMER_OR_CONFIGURATION);
    }

    private static Set<ObservationOutcome> allObservationOutcomes() {
      return Set.of(ObservationOutcome.values());
    }

    private static Set<ExternalClassification> startClassifications() {
      return Set.of(ExternalClassification.values());
    }

    private static Set<ExternalClassification> callbackClassifications() {
      return Set.of(
          ExternalClassification.SUCCESS_200_OR_201,
          ExternalClassification.CONCEALMENT_404,
          ExternalClassification.DURABLE_OR_INTENT_CONFLICT_409,
          ExternalClassification.INDETERMINATE_429,
          ExternalClassification.DEPENDENCY_UNAVAILABLE_503,
          ExternalClassification.SANDBOX_REJECTION_403,
          ExternalClassification.UNEXPECTED_FAILURE_500);
    }
  }
}
