package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.citybuddy.commerce.mysql.BoundedMySqlTransactions;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MockPaymentTransactionsTest {
  @Test
  void registeredEntriesAreClosedAndRuntimeModeChecksAreEnforced() {
    BoundedMySqlTransactions bounded = mock(BoundedMySqlTransactions.class);
    MockPaymentTransactions transactions = new MockPaymentTransactions(bounded);

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
                .filter(entry -> entry.mode() == MockPaymentTransactions.Mode.MUTATION))
        .containsExactlyInAnyOrder(
            MockPaymentTransactions.Entry.START_INITIAL_MUTATION,
            MockPaymentTransactions.Entry.START_FINAL_MUTATION,
            MockPaymentTransactions.Entry.CALLBACK_INITIAL_MUTATION);

    assertThatThrownBy(
            () ->
                transactions.observe(
                    MockPaymentTransactions.Entry.START_INITIAL_MUTATION, () -> "wrong mode"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not an observation");
    assertThatThrownBy(
            () ->
                transactions.mutate(
                    MockPaymentTransactions.Entry.START_TRUTH_OBSERVATION, () -> "wrong mode"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a mutation");
  }
}
