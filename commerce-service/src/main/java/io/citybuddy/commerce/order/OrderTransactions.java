package io.citybuddy.commerce.order;

import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

final class OrderTransactions {
  private final OrderRepository repository;
  private final TransactionTemplate transactions;
  private final int lockWaitTimeoutSeconds;

  OrderTransactions(
      OrderRepository repository, TransactionTemplate transactions, int lockWaitTimeoutSeconds) {
    this.repository = repository;
    this.transactions = transactions;
    this.lockWaitTimeoutSeconds = lockWaitTimeoutSeconds;
  }

  <T> T mutate(TransactionCallback<T> work) {
    return execute(work);
  }

  <T> T observe(TransactionCallback<T> work) {
    return execute(work);
  }

  private <T> T execute(TransactionCallback<T> work) {
    return transactions.execute(
        status ->
            repository.withLockWaitTimeout(
                lockWaitTimeoutSeconds, () -> work.doInTransaction(status)));
  }
}
