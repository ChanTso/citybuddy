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
  private static final Set<String> OWNER_FIELDS =
      Set.of("owner", "ownersubject", "user", "userid", "usersubject");
  private final OrderRepository repository;
  private final TransactionTemplate transactions;
  private final OrderProperties properties;

  public OrderService(
      OrderRepository repository, TransactionTemplate transactions, OrderProperties properties) {
    this.repository = repository;
    this.transactions = transactions;
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
    try {
      OrderResult committed =
          transactions.execute(
              status -> resolveCommitted(user, idempotencyKey, request, correlationId));
      if (committed != null) {
        return committed;
      }
    } catch (PessimisticLockingFailureException exception) {
      // The bounded mutation attempts are exhausted; this final transaction only observes truth.
    } catch (DataAccessResourceFailureException | CannotCreateTransactionException exception) {
      throw unavailable(correlationId);
    }
    throw failure(
        409,
        OrderCategory.CONCURRENCY_EXHAUSTED,
        "Order concurrency retry bound exhausted without a committed result",
        correlationId);
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

  private record ValidatedRequest(
      String productId, int quantity, long expectedProductVersion, String intentHash) {}

  static final class StockRaceException extends RuntimeException {}
}
