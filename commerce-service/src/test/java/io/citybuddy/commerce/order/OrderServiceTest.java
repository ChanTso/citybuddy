package io.citybuddy.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class OrderServiceTest {
  private OrderRepository repository;
  private PlatformTransactionManager transactionManager;
  private OrderService service;

  @BeforeEach
  void setUp() {
    repository = mock(OrderRepository.class);
    transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    service =
        new OrderService(
            repository,
            new TransactionTemplate(transactionManager),
            new OrderProperties("order:create", 5, 3));
  }

  @Test
  void createsSnapshotStockIdempotencyAndOutboxInOneAttempt() {
    OrderRequest request = request("product-1", 2, 7);
    OrderRepository.ProductSnapshot product = product(4, 7);
    when(repository.findIdempotencyForUpdate("user-1", "key-1")).thenReturn(Optional.empty());
    when(repository.findProduct("product-1")).thenReturn(Optional.of(product));
    when(repository.decrementStock(product, 2)).thenReturn(true);
    when(repository.findOwnedOrder(eq("user-1"), anyString(), eq("corr-1")))
        .thenReturn(result("corr-1", false));

    OrderResult result = service.create("user-1", "key-1", request, "corr-1");

    assertThat(result.replayed()).isFalse();
    var ordered = inOrder(repository);
    ordered.verify(repository).findIdempotencyForUpdate("user-1", "key-1");
    ordered
        .verify(repository)
        .reserveIdempotency(eq("user-1"), eq("key-1"), anyString(), anyString());
    ordered.verify(repository).findProduct("product-1");
    ordered.verify(repository).decrementStock(product, 2);
    ordered.verify(repository).insertOrder(eq("user-1"), anyString(), eq(product), eq(2));
    ordered.verify(repository).insertOutbox(anyString(), eq(product), eq(2));
    ordered.verify(repository).findOwnedOrder(eq("user-1"), anyString(), eq("corr-1"));
    verify(transactionManager).commit(any());
  }

  @Test
  void sameScopedKeyAndIntentReplaysWithoutStockOrOutboxMutation() {
    OrderRequest request = request("product-1", 2, 7);
    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    when(repository.findIdempotencyForUpdate("user-1", "key-1")).thenReturn(Optional.empty());
    when(repository.findProduct("product-1")).thenReturn(Optional.of(product(4, 7)));
    when(repository.decrementStock(any(), eq(2))).thenReturn(true);
    when(repository.findOwnedOrder(eq("user-1"), anyString(), anyString()))
        .thenReturn(result("first", false));
    service.create("user-1", "key-1", request, "first");
    verify(repository).reserveIdempotency(eq("user-1"), eq("key-1"), hash.capture(), anyString());

    when(repository.findIdempotencyForUpdate("user-1", "key-1"))
        .thenReturn(Optional.of(new OrderRepository.IdempotencyRecord(hash.getValue(), "order-1")));
    when(repository.findOwnedOrder("user-1", "order-1", "second"))
        .thenReturn(result("second", false));

    OrderResult replay = service.create("user-1", "key-1", request, "second");

    assertThat(replay.replayed()).isTrue();
    verify(repository, times(1)).decrementStock(any(), eq(2));
    verify(repository, times(1)).insertOutbox(anyString(), any(), eq(2));
  }

  @Test
  void conflictingIntentRejectsBeforeAuthoritativeMutation() {
    when(repository.findIdempotencyForUpdate("user-1", "key-1"))
        .thenReturn(Optional.of(new OrderRepository.IdempotencyRecord("different", "order-1")));

    assertThatThrownBy(() -> service.create("user-1", "key-1", request("product-1", 2, 7), "corr"))
        .isInstanceOfSatisfying(
            OrderException.class,
            exception ->
                assertThat(exception.category()).isEqualTo(OrderCategory.IDEMPOTENCY_CONFLICT));

    verify(repository, never()).findProduct(anyString());
    verify(repository, never()).decrementStock(any(), eq(2));
  }

  @Test
  void ownerAndClientAuthorityFieldsRejectBeforeTransaction() {
    OrderRequest owner = request("product-1", 1, 1);
    owner.extraField("userSubject", "other-user");

    assertThatThrownBy(() -> service.create("user-1", "key", owner, "corr"))
        .isInstanceOfSatisfying(
            OrderException.class,
            exception -> assertThat(exception.category()).isEqualTo(OrderCategory.OWNERSHIP));
    verifyNoInteractions(repository, transactionManager);

    OrderRequest price = request("product-1", 1, 1);
    price.extraField("priceMinor", 1);
    assertThatThrownBy(() -> service.create("user-1", "key", price, "corr"))
        .isInstanceOfSatisfying(
            OrderException.class,
            exception -> assertThat(exception.category()).isEqualTo(OrderCategory.VALIDATION));
    verifyNoInteractions(repository, transactionManager);
  }

  @Test
  void retriesOnlyRecognizedConcurrencyAndRevalidatesMysql() {
    when(repository.findIdempotencyForUpdate("user-1", "key-1"))
        .thenThrow(new CannotAcquireLockException("controlled lock conflict"))
        .thenReturn(Optional.empty());
    OrderRepository.ProductSnapshot product = product(2, 7);
    when(repository.findProduct("product-1")).thenReturn(Optional.of(product));
    when(repository.decrementStock(product, 1)).thenReturn(true);
    when(repository.findOwnedOrder(eq("user-1"), anyString(), eq("corr")))
        .thenReturn(result("corr", false));

    service.create("user-1", "key-1", request("product-1", 1, 7), "corr");

    verify(repository, times(2)).findIdempotencyForUpdate("user-1", "key-1");
    verify(repository).findProduct("product-1");
    verify(transactionManager).rollback(any());
    verify(transactionManager).commit(any());
  }

  @Test
  void recognizedConcurrencyExhaustsAtConfiguredBound() {
    when(repository.findIdempotencyForUpdate("user-1", "key-1"))
        .thenThrow(new CannotAcquireLockException("controlled lock conflict"));

    assertThatThrownBy(() -> service.create("user-1", "key-1", request("product-1", 1, 7), "corr"))
        .isInstanceOfSatisfying(
            OrderException.class,
            exception ->
                assertThat(exception.category()).isEqualTo(OrderCategory.CONCURRENCY_EXHAUSTED));

    verify(repository, times(3)).findIdempotencyForUpdate("user-1", "key-1");
    verify(transactionManager, times(3)).rollback(any());
  }

  @Test
  void unexpectedDatabaseFailureStaysVisibleAndIsNotRetried() {
    DataAccessResourceFailureException failure =
        new DataAccessResourceFailureException("controlled non-concurrency failure");
    when(repository.findIdempotencyForUpdate("user-1", "key-1")).thenThrow(failure);

    assertThatThrownBy(() -> service.create("user-1", "key-1", request("product-1", 1, 7), "corr"))
        .isSameAs(failure);

    verify(repository).findIdempotencyForUpdate("user-1", "key-1");
    verify(transactionManager).rollback(any());
  }

  private static OrderRequest request(String productId, int quantity, long version) {
    OrderRequest request = new OrderRequest();
    request.setProductId(productId);
    request.setQuantity(quantity);
    request.setExpectedProductVersion(version);
    return request;
  }

  private static OrderRepository.ProductSnapshot product(long stock, long version) {
    return new OrderRepository.ProductSnapshot(
        "product-1", "Product", 500, "AUD", stock, true, "PUBLISHED", version);
  }

  private static OrderResult result(String correlationId, boolean replayed) {
    return new OrderResult(
        "order-1",
        "product-1",
        "Product",
        500,
        "AUD",
        2,
        1000,
        7,
        "UNPAID",
        correlationId,
        replayed);
  }
}
