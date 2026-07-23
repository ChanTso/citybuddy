package io.citybuddy.commerce.order;

import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

final class OrderTransactions {
  private final OrderRepository repository;
  private final TransactionTemplate mutations;
  private final TransactionTemplate observations;
  private final int lockWaitTimeoutSeconds;

  OrderTransactions(
      OrderRepository repository, TransactionTemplate transactions, int lockWaitTimeoutSeconds) {
    this.repository = repository;
    this.mutations = transactions;
    this.observations = new TransactionTemplate(transactions.getTransactionManager(), transactions);
    this.observations.setTimeout(lockWaitTimeoutSeconds);
    this.lockWaitTimeoutSeconds = lockWaitTimeoutSeconds;
  }

  <T> T mutate(TransactionCallback<T> work) {
    return execute(mutations, work);
  }

  <T> T observe(TransactionCallback<T> work) {
    return execute(observations, work);
  }

  private <T> T execute(TransactionTemplate transactions, TransactionCallback<T> work) {
    return transactions.execute(
        status ->
            repository.withLockWaitTimeout(
                lockWaitTimeoutSeconds, () -> work.doInTransaction(status)));
  }
}
