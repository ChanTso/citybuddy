package io.citybuddy.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class OrderTransactionsTest {
  @Test
  void mutationAndObservationTransactionsShareTheConfiguredSessionLockBoundary() {
    OrderRepository repository = mock(OrderRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    when(repository.withLockWaitTimeout(eq(2), any()))
        .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
    OrderTransactions transactions =
        new OrderTransactions(repository, new TransactionTemplate(transactionManager), 2);

    String mutation = transactions.mutate(status -> "mutation");
    String observation = transactions.observe(status -> "observation");

    assertThat(mutation).isEqualTo("mutation");
    assertThat(observation).isEqualTo("observation");

    verify(repository, times(2)).withLockWaitTimeout(eq(2), any());
    verify(transactionManager, times(2)).commit(any());
  }
}
