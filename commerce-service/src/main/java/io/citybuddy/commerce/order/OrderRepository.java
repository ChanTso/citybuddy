package io.citybuddy.commerce.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class OrderRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public OrderRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  public Optional<IdempotencyRecord> findIdempotencyForUpdate(String user, String key) {
    return jdbc
        .query(
            """
            SELECT intent_hash, order_id
            FROM order_idempotency
            WHERE user_subject = ? AND idempotency_key = ?
            FOR UPDATE
            """,
            (result, row) ->
                new IdempotencyRecord(
                    result.getString("intent_hash"), result.getString("order_id")),
            user,
            key)
        .stream()
        .findFirst();
  }

  <T> T withLockWaitTimeout(int timeoutSeconds, Supplier<T> work) {
    if (timeoutSeconds < 1 || timeoutSeconds > 60) {
      throw new IllegalArgumentException("Lock wait timeout must be between 1 and 60 seconds");
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Lock wait timeout guard requires an active transaction");
    }
    Long previous = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Long.class);
    if (previous == null) {
      throw new IllegalStateException("MySQL lock wait timeout is unavailable");
    }
    try {
      jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + timeoutSeconds);
      return work.get();
    } finally {
      jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + previous);
    }
  }

  public void reserveIdempotency(String user, String key, String intentHash, String orderId) {
    try {
      jdbc.update(
          """
          INSERT INTO order_idempotency (user_subject, idempotency_key, intent_hash, order_id)
          VALUES (?, ?, ?, ?)
          """,
          user,
          key,
          intentHash,
          orderId);
    } catch (DuplicateKeyException exception) {
      throw new IdempotencyRaceException(exception);
    }
  }

  public Optional<ProductSnapshot> findProduct(String productId) {
    return jdbc
        .query(
            """
            SELECT product_id, name, price_minor, currency, stock_quantity, available,
                   publication_state, publication_version
            FROM product
            WHERE product_id = ?
            """,
            (result, row) ->
                new ProductSnapshot(
                    result.getString("product_id"),
                    result.getString("name"),
                    result.getLong("price_minor"),
                    result.getString("currency"),
                    result.getLong("stock_quantity"),
                    result.getBoolean("available"),
                    result.getString("publication_state"),
                    result.getLong("publication_version")),
            productId)
        .stream()
        .findFirst();
  }

  public boolean decrementStock(ProductSnapshot product, int quantity) {
    return jdbc.update(
            """
            UPDATE product
            SET stock_quantity = stock_quantity - ?
            WHERE product_id = ?
              AND publication_state = 'PUBLISHED'
              AND available = TRUE
              AND publication_version = ?
              AND stock_quantity = ?
              AND stock_quantity >= ?
            """,
            quantity,
            product.productId(),
            product.publicationVersion(),
            product.stockQuantity(),
            quantity)
        == 1;
  }

  public void insertOrder(String user, String orderId, ProductSnapshot product, int quantity) {
    long total = Math.multiplyExact(product.priceMinor(), quantity);
    jdbc.update(
        """
        INSERT INTO standard_order
          (order_id, user_subject, product_id, product_name, unit_price_minor, currency,
           quantity, total_price_minor, product_version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        orderId,
        user,
        product.productId(),
        product.name(),
        product.priceMinor(),
        product.currency(),
        quantity,
        total,
        product.publicationVersion());
  }

  public void insertOutbox(String orderId, ProductSnapshot product, int quantity) {
    String eventId = java.util.UUID.randomUUID().toString();
    var event =
        new OrderCreatedEvent(
            eventId,
            orderId,
            product.productId(),
            quantity,
            product.priceMinor(),
            product.currency(),
            product.publicationVersion());
    jdbc.update(
        """
        INSERT INTO commerce_outbox
          (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload)
        VALUES (?, 'STANDARD_ORDER', ?, 1, 'STANDARD_ORDER_CREATED', CAST(? AS JSON))
        """,
        eventId,
        orderId,
        json(event));
  }

  public OrderResult findOwnedOrder(String user, String orderId, String correlationId) {
    List<OrderResult> rows =
        jdbc.query(
            """
            SELECT order_id, product_id, product_name, unit_price_minor, currency, quantity,
                   total_price_minor, product_version, status
            FROM standard_order
            WHERE user_subject = ? AND order_id = ?
            """,
            (result, row) ->
                new OrderResult(
                    result.getString("order_id"),
                    result.getString("product_id"),
                    result.getString("product_name"),
                    result.getLong("unit_price_minor"),
                    result.getString("currency"),
                    result.getInt("quantity"),
                    result.getLong("total_price_minor"),
                    result.getLong("product_version"),
                    result.getString("status"),
                    correlationId,
                    false),
            user,
            orderId);
    if (rows.size() != 1) {
      throw new IllegalStateException("Idempotency result is missing its owned order");
    }
    return rows.getFirst();
  }

  private String json(OrderCreatedEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Order event serialization failed", exception);
    }
  }

  public record IdempotencyRecord(String intentHash, String orderId) {}

  public record ProductSnapshot(
      String productId,
      String name,
      long priceMinor,
      String currency,
      long stockQuantity,
      boolean available,
      String publicationState,
      long publicationVersion) {}

  record OrderCreatedEvent(
      String eventId,
      String orderId,
      String productId,
      int quantity,
      long unitPriceMinor,
      String currency,
      long productVersion) {}

  static final class IdempotencyRaceException extends RuntimeException {
    IdempotencyRaceException(DuplicateKeyException cause) {
      super(cause);
    }
  }
}
