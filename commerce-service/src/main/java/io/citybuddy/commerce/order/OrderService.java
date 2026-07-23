package io.citybuddy.commerce.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

public final class OrderService {
  private static final int COMMITTED_OBSERVATION_ATTEMPTS = 3;
  private static final int COMMITTED_OBSERVATION_TIMEOUT_SECONDS = 1;
  private static final long COMMITTED_OBSERVATION_BACKOFF_MILLIS = 25;
  private static final Set<String> OWNER_FIELDS =
      Set.of("owner", "ownersubject", "user", "userid", "usersubject");
  private final OrderRepository repository;
  private final TransactionTemplate transactions;
  private final TransactionTemplate observationTransactions;
  private final OrderProperties properties;

  public OrderService(
      OrderRepository repository, TransactionTemplate transactions, OrderProperties properties) {
    this.repository = repository;
    this.transactions = transactions;
    this.observationTransactions =
        new TransactionTemplate(transactions.getTransactionManager(), transactions);
    this.observationTransactions.setTimeout(COMMITTED_OBSERVATION_TIMEOUT_SECONDS);
    this.properties = properties;
  }

  public OrderResult create(
      String user, String idempotencyKey, OrderRequest request, String correlationId) {
    ValidatedRequest validated = validate(idempotencyKey, request, correlationId);
    for (int attempt = 1; attempt <= properties.maximumConcurrencyAttempts(); attempt++) {
      try {
        OrderResult result =
            transactions.execute(
                status -> createOnce(user, idempotencyKey, validated, correlationId));
        if (result == null) {
          throw new IllegalStateException("Order transaction returned no result");
        }
        return result;
      } catch (PessimisticLockingFailureException
          | OrderRepository.IdempotencyRaceException
          | StockRaceException exception) {
        if (attempt == properties.maximumConcurrencyAttempts()) {
          return resolveCommittedAfterConcurrency(user, idempotencyKey, validated, correlationId);
        }
      } catch (DataAccessResourceFailureException | CannotCreateTransactionException exception) {
        throw unavailable(correlationId);
      }
    }
    throw new IllegalStateException("Unreachable order retry state");
  }

  private OrderResult createOnce(
      String user, String idempotencyKey, ValidatedRequest request, String correlationId) {
    OrderResult committed = resolveCommitted(user, idempotencyKey, request, correlationId);
    if (committed != null) {
      return committed;
    }

    String orderId = UUID.randomUUID().toString();
    repository.reserveIdempotency(user, idempotencyKey, request.intentHash(), orderId);
    OrderRepository.ProductSnapshot product =
        repository
            .findProduct(request.productId())
            .orElseThrow(
                () ->
                    failure(
                        422,
                        OrderCategory.VALIDATION,
                        "Product is missing or not orderable",
                        correlationId));
    if (!"PUBLISHED".equals(product.publicationState()) || !product.available()) {
      throw failure(
          422, OrderCategory.VALIDATION, "Product is missing or not orderable", correlationId);
    }
    if (product.publicationVersion() != request.expectedProductVersion()) {
      throw failure(409, OrderCategory.STALE_VERSION, "Product version is stale", correlationId);
    }
    if (product.stockQuantity() < request.quantity()) {
      throw failure(
          409, OrderCategory.INSUFFICIENT_STOCK, "Insufficient authoritative stock", correlationId);
    }
    Math.multiplyExact(product.priceMinor(), request.quantity());
    if (!repository.decrementStock(product, request.quantity())) {
      throw new StockRaceException();
    }
    repository.insertOrder(user, orderId, product, request.quantity());
    repository.insertOutbox(orderId, product, request.quantity());
    return repository.findOwnedOrder(user, orderId, correlationId);
  }

  private OrderResult resolveCommittedAfterConcurrency(
      String user, String idempotencyKey, ValidatedRequest request, String correlationId) {
    CommittedObservation observation =
        observeCommittedWithinBound(user, idempotencyKey, request, correlationId);
    if (observation.state() == CommittedObservationState.FOUND) {
      return observation.result();
    }
    if (observation.state() == CommittedObservationState.INDETERMINATE) {
      throw retryableConcurrency(correlationId);
    }

    try {
      OrderResult result =
          transactions.execute(status -> createOnce(user, idempotencyKey, request, correlationId));
      if (result == null) {
        throw new IllegalStateException("Order recovery transaction returned no result");
      }
      return result;
    } catch (PessimisticLockingFailureException
        | OrderRepository.IdempotencyRaceException
        | StockRaceException exception) {
      observation = observeCommittedWithinBound(user, idempotencyKey, request, correlationId);
      if (observation.state() == CommittedObservationState.FOUND) {
        return observation.result();
      }
      throw retryableConcurrency(correlationId);
    } catch (DataAccessResourceFailureException | CannotCreateTransactionException exception) {
      throw unavailable(correlationId);
    }
  }

  private CommittedObservation observeCommittedWithinBound(
      String user, String idempotencyKey, ValidatedRequest request, String correlationId) {
    for (int attempt = 1; attempt <= COMMITTED_OBSERVATION_ATTEMPTS; attempt++) {
      CommittedObservation observation =
          observeCommitted(user, idempotencyKey, request, correlationId);
      if (observation.state() != CommittedObservationState.INDETERMINATE) {
        return observation;
      }
      if (attempt < COMMITTED_OBSERVATION_ATTEMPTS && !pauseBeforeObservation(attempt)) {
        return CommittedObservation.indeterminate();
      }
    }
    return CommittedObservation.indeterminate();
  }

  private CommittedObservation observeCommitted(
      String user, String idempotencyKey, ValidatedRequest request, String correlationId) {
    try {
      OrderResult committed =
          observationTransactions.execute(
              status -> resolveCommitted(user, idempotencyKey, request, correlationId));
      return committed == null
          ? CommittedObservation.confirmedAbsent()
          : CommittedObservation.found(committed);
    } catch (PessimisticLockingFailureException exception) {
      return CommittedObservation.indeterminate();
    } catch (DataAccessResourceFailureException | CannotCreateTransactionException exception) {
      throw unavailable(correlationId);
    }
  }

  private static boolean pauseBeforeObservation(int attempt) {
    try {
      Thread.sleep(COMMITTED_OBSERVATION_BACKOFF_MILLIS * attempt);
      return true;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private OrderResult resolveCommitted(
      String user, String idempotencyKey, ValidatedRequest request, String correlationId) {
    var existing = repository.findIdempotencyForUpdate(user, idempotencyKey);
    if (existing.isEmpty()) {
      return null;
    }
    if (!existing.get().intentHash().equals(request.intentHash())) {
      throw failure(
          409,
          OrderCategory.IDEMPOTENCY_CONFLICT,
          "Idempotency key is already bound to a different order intent",
          correlationId);
    }
    return repository.findOwnedOrder(user, existing.get().orderId(), correlationId).asReplay();
  }

  private ValidatedRequest validate(
      String idempotencyKey, OrderRequest request, String correlationId) {
    if (request == null) {
      throw failure(400, OrderCategory.VALIDATION, "Order body is required", correlationId);
    }
    if (request.extraFields().keySet().stream()
        .map(value -> value.toLowerCase(java.util.Locale.ROOT))
        .anyMatch(OWNER_FIELDS::contains)) {
      throw failure(
          403,
          OrderCategory.OWNERSHIP,
          "Order owner is derived from authenticated identity",
          correlationId);
    }
    if (!request.extraFields().isEmpty()) {
      throw failure(
          400, OrderCategory.VALIDATION, "Order body contains unsupported fields", correlationId);
    }
    String productId = request.getProductId();
    Integer quantity = request.getQuantity();
    Long version = request.getExpectedProductVersion();
    if (idempotencyKey == null
        || idempotencyKey.isBlank()
        || idempotencyKey.length() > 128
        || productId == null
        || productId.isBlank()
        || productId.length() > 64
        || quantity == null
        || quantity < 1
        || quantity > properties.maximumQuantity()
        || version == null
        || version < 1) {
      throw failure(400, OrderCategory.VALIDATION, "Order request is invalid", correlationId);
    }
    String normalizedProductId = productId.strip();
    String canonical =
        normalizedProductId.length() + ":" + normalizedProductId + ":" + quantity + ":" + version;
    return new ValidatedRequest(normalizedProductId, quantity, version, sha256(canonical));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static OrderException failure(
      int status, OrderCategory category, String message, String correlationId) {
    return new OrderException(status, category, message, correlationId);
  }

  private static OrderException unavailable(String correlationId) {
    return failure(
        503, OrderCategory.DEPENDENCY_UNAVAILABLE, "Order database is unavailable", correlationId);
  }

  private static OrderException retryableConcurrency(String correlationId) {
    return failure(
        429,
        OrderCategory.CONCURRENCY_EXHAUSTED,
        "Order concurrency is indeterminate; retry the same idempotency key",
        correlationId);
  }

  private record ValidatedRequest(
      String productId, int quantity, long expectedProductVersion, String intentHash) {}

  private enum CommittedObservationState {
    FOUND,
    CONFIRMED_ABSENT,
    INDETERMINATE
  }

  private record CommittedObservation(CommittedObservationState state, OrderResult result) {
    private static CommittedObservation found(OrderResult result) {
      return new CommittedObservation(CommittedObservationState.FOUND, result);
    }

    private static CommittedObservation confirmedAbsent() {
      return new CommittedObservation(CommittedObservationState.CONFIRMED_ABSENT, null);
    }

    private static CommittedObservation indeterminate() {
      return new CommittedObservation(CommittedObservationState.INDETERMINATE, null);
    }
  }

  static final class StockRaceException extends RuntimeException {}
}
