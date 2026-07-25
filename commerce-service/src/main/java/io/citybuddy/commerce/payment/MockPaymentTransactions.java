package io.citybuddy.commerce.payment;

import io.citybuddy.commerce.mysql.BoundedMySqlTransactions;
import java.util.Set;
import java.util.function.Supplier;

final class MockPaymentTransactions {
  private final BoundedMySqlTransactions transactions;

  MockPaymentTransactions(BoundedMySqlTransactions transactions) {
    this.transactions = transactions;
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
    return transactions.execute(work);
  }

  enum Mode {
    MUTATION,
    OBSERVATION
  }

  enum ObservationOutcome {
    FOUND,
    CONFIRMED_ABSENT,
    INDETERMINATE
  }

  enum Entry {
    START_INITIAL_MUTATION(Mode.MUTATION),
    START_TRUTH_OBSERVATION(Mode.OBSERVATION),
    START_FINAL_MUTATION(Mode.MUTATION),
    START_FINAL_OBSERVATION(Mode.OBSERVATION),
    CALLBACK_INITIAL_MUTATION(Mode.MUTATION),
    CALLBACK_TRUTH_OBSERVATION(Mode.OBSERVATION);

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
