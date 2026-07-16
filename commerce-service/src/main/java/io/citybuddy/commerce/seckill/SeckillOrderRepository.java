package io.citybuddy.commerce.seckill;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class SeckillOrderRepository {
  private final JdbcTemplate jdbc;

  public SeckillOrderRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<ProductSnapshot> findProductForUpdate(String productId) {
    return jdbc
        .query(
            """
            SELECT product_id, name, price_minor, currency, stock_quantity,
                   available, publication_state, publication_version
            FROM product
            WHERE product_id = ?
            FOR UPDATE
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

  public boolean decrementInventory(ProductSnapshot product, int quantity) {
    return jdbc.update(
            """
            UPDATE product
            SET stock_quantity = stock_quantity - ?
            WHERE product_id = ?
              AND stock_quantity = ?
              AND stock_quantity >= ?
              AND publication_version = ?
            """,
            quantity,
            product.productId(),
            product.stockQuantity(),
            quantity,
            product.publicationVersion())
        == 1;
  }

  public void restoreInventory(ProductSnapshot product, int quantity) {
    int changed =
        jdbc.update(
            """
            UPDATE product
            SET stock_quantity = stock_quantity + ?
            WHERE product_id = ? AND stock_quantity = ? AND publication_version = ?
            """,
            quantity,
            product.productId(),
            product.stockQuantity(),
            product.publicationVersion());
    if (changed != 1) {
      throw new IllegalStateException("Authoritative inventory changed during restoration");
    }
  }

  public void insertOrder(OrderRecord order) {
    jdbc.update(
        """
        INSERT INTO seckill_order
          (order_id, reservation_id, transaction_event_id, timeout_event_id, user_subject,
           activity_id, product_id, product_name, unit_price_minor, currency, quantity,
           total_price_minor, unpaid_deadline)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        order.orderId(),
        order.reservationId(),
        order.transactionEventId(),
        order.timeoutEventId(),
        order.userSubject(),
        order.activityId(),
        order.productId(),
        order.productName(),
        order.unitPriceMinor(),
        order.currency(),
        order.quantity(),
        order.totalPriceMinor(),
        Timestamp.from(order.unpaidDeadline()));
  }

  public void insertOrderCreateMovement(OrderRecord order) {
    long delta = -Math.toIntExact(order.quantity());
    jdbc.update(
        """
        INSERT INTO inventory_ledger
          (movement_id, business_event_key, movement_type, order_id, reservation_id,
           activity_id, product_id, inventory_delta, activity_quota_delta)
        VALUES (?, ?, 'SECKILL_ORDER_CREATE', ?, ?, ?, ?, ?, ?)
        """,
        java.util.UUID.randomUUID().toString(),
        "seckill-order-create:" + order.transactionEventId(),
        order.orderId(),
        order.reservationId(),
        order.activityId(),
        order.productId(),
        delta,
        delta);
  }

  public void insertUnpaidCancellationMovement(OrderRecord order) {
    long delta = Math.toIntExact(order.quantity());
    jdbc.update(
        """
        INSERT INTO inventory_ledger
          (movement_id, business_event_key, movement_type, order_id, reservation_id,
           activity_id, product_id, inventory_delta, activity_quota_delta)
        VALUES (?, ?, 'SECKILL_UNPAID_CANCEL', ?, ?, ?, ?, ?, ?)
        """,
        java.util.UUID.randomUUID().toString(),
        "seckill-unpaid-cancel:" + order.timeoutEventId(),
        order.orderId(),
        order.reservationId(),
        order.activityId(),
        order.productId(),
        delta,
        delta);
  }

  public Optional<OrderRecord> findByReservation(String reservationId) {
    return query(
        "SELECT " + columns() + " FROM seckill_order WHERE reservation_id = ?", reservationId);
  }

  public Optional<OrderRecord> findByActivityUser(String activityId, String userSubject) {
    return query(
        "SELECT " + columns() + " FROM seckill_order WHERE activity_id = ? AND user_subject = ?",
        activityId,
        userSubject);
  }

  public Optional<OrderRecord> findForUpdate(String orderId) {
    return query(
        "SELECT " + columns() + " FROM seckill_order WHERE order_id = ? FOR UPDATE", orderId);
  }

  public List<OrderRecord> findPendingTimeoutDispatches(
      Instant createdNotAfter, int maximumAttempts, int limit) {
    if (maximumAttempts < 1 || limit < 1 || limit > 1_000) {
      throw new IllegalArgumentException("Timeout dispatch bounds are invalid");
    }
    String cutoff = createdNotAfter == null ? "" : " AND created_at <= ?";
    Object[] arguments =
        createdNotAfter == null
            ? new Object[] {maximumAttempts, limit}
            : new Object[] {maximumAttempts, Timestamp.from(createdNotAfter), limit};
    return jdbc.query(
        "SELECT "
            + columns()
            + " FROM seckill_order WHERE timeout_dispatch_state = 'PENDING' "
            + "AND status = 'UNPAID' AND timeout_dispatch_attempts < ?"
            + cutoff
            + " ORDER BY created_at, order_id LIMIT ?",
        SeckillOrderRepository::mapOrder,
        arguments);
  }

  public void markTimeoutDispatched(OrderRecord order, String brokerMessageId) {
    int changed =
        jdbc.update(
            """
            UPDATE seckill_order
            SET timeout_dispatch_state = 'SENT', timeout_broker_message_id = ?,
                timeout_dispatched_at = CURRENT_TIMESTAMP(6), timeout_dispatch_error = NULL
            WHERE order_id = ? AND timeout_event_id = ? AND timeout_dispatch_state = 'PENDING'
            """,
            brokerMessageId,
            order.orderId(),
            order.timeoutEventId());
    if (changed != 1) {
      OrderRecord current =
          findForUpdate(order.orderId())
              .orElseThrow(() -> new IllegalStateException("Dispatched order truth is missing"));
      if (!"SENT".equals(current.timeoutDispatchState())
          || !order.timeoutEventId().equals(current.timeoutEventId())) {
        throw new IllegalStateException("Timeout dispatch state changed before send evidence");
      }
    }
  }

  public void recordTimeoutDispatchFailure(OrderRecord order, int maximumAttempts, String failure) {
    int nextAttempt = Math.addExact(order.timeoutDispatchAttempts(), 1);
    String state = nextAttempt >= maximumAttempts ? "FAILED" : "PENDING";
    int changed =
        jdbc.update(
            """
            UPDATE seckill_order
            SET timeout_dispatch_state = ?, timeout_dispatch_attempts = ?,
                timeout_dispatch_error = ?
            WHERE order_id = ? AND timeout_event_id = ? AND timeout_dispatch_state = 'PENDING'
              AND timeout_dispatch_attempts = ?
            """,
            state,
            nextAttempt,
            abbreviate(failure, 500),
            order.orderId(),
            order.timeoutEventId(),
            order.timeoutDispatchAttempts());
    if (changed != 1) {
      throw new IllegalStateException("Timeout dispatch failure state changed concurrently");
    }
  }

  public OrderRecord markCancelled(OrderRecord order, long cancellationProjectionVersion) {
    int changed =
        jdbc.update(
            """
            UPDATE seckill_order
            SET status = 'CANCELLED', state_version = 2, cancellation_projection_version = ?
            WHERE order_id = ? AND status = 'UNPAID' AND state_version = 1
            """,
            cancellationProjectionVersion,
            order.orderId());
    if (changed != 1) {
      throw new IllegalStateException("Seckill order changed during its locked cancellation");
    }
    return order.withState("CANCELLED", 2, cancellationProjectionVersion);
  }

  public boolean hasUnpaidCancellationMovement(String orderId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger "
                + "WHERE order_id = ? AND movement_type = 'SECKILL_UNPAID_CANCEL'",
            Integer.class,
            orderId);
    return count != null && count == 1;
  }

  private Optional<OrderRecord> query(String sql, Object... arguments) {
    List<OrderRecord> rows = jdbc.query(sql, SeckillOrderRepository::mapOrder, arguments);
    if (rows.size() > 1) {
      throw new IllegalStateException("Seckill order uniqueness is corrupted");
    }
    return rows.stream().findFirst();
  }

  private static String columns() {
    return "order_id, reservation_id, transaction_event_id, timeout_event_id, user_subject, "
        + "activity_id, product_id, product_name, unit_price_minor, currency, quantity, "
        + "total_price_minor, status, state_version, cancellation_projection_version, "
        + "unpaid_deadline, timeout_dispatch_state, "
        + "timeout_dispatch_attempts, timeout_broker_message_id, timeout_dispatched_at, "
        + "timeout_dispatch_error";
  }

  private static OrderRecord mapOrder(java.sql.ResultSet result, int row)
      throws java.sql.SQLException {
    Timestamp dispatched = result.getTimestamp("timeout_dispatched_at");
    return new OrderRecord(
        result.getString("order_id"),
        result.getString("reservation_id"),
        result.getString("transaction_event_id"),
        result.getString("timeout_event_id"),
        result.getString("user_subject"),
        result.getString("activity_id"),
        result.getString("product_id"),
        result.getString("product_name"),
        result.getLong("unit_price_minor"),
        result.getString("currency"),
        result.getInt("quantity"),
        result.getLong("total_price_minor"),
        result.getString("status"),
        result.getLong("state_version"),
        result.getObject("cancellation_projection_version", Long.class),
        result.getTimestamp("unpaid_deadline").toInstant(),
        result.getString("timeout_dispatch_state"),
        result.getInt("timeout_dispatch_attempts"),
        result.getString("timeout_broker_message_id"),
        dispatched == null ? null : dispatched.toInstant(),
        result.getString("timeout_dispatch_error"));
  }

  private static String abbreviate(String value, int maximumLength) {
    if (value == null || value.isBlank()) {
      return "unknown dispatch failure";
    }
    return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
  }

  public record ProductSnapshot(
      String productId,
      String name,
      long priceMinor,
      String currency,
      long stockQuantity,
      boolean available,
      String publicationState,
      long publicationVersion) {}

  public record OrderRecord(
      String orderId,
      String reservationId,
      String transactionEventId,
      String timeoutEventId,
      String userSubject,
      String activityId,
      String productId,
      String productName,
      long unitPriceMinor,
      String currency,
      int quantity,
      long totalPriceMinor,
      String status,
      long stateVersion,
      Long cancellationProjectionVersion,
      Instant unpaidDeadline,
      String timeoutDispatchState,
      int timeoutDispatchAttempts,
      String timeoutBrokerMessageId,
      Instant timeoutDispatchedAt,
      String timeoutDispatchError) {

    public OrderRecord(
        String orderId,
        String reservationId,
        String transactionEventId,
        String timeoutEventId,
        String userSubject,
        String activityId,
        String productId,
        String productName,
        long unitPriceMinor,
        String currency,
        int quantity,
        long totalPriceMinor,
        Instant unpaidDeadline) {
      this(
          orderId,
          reservationId,
          transactionEventId,
          timeoutEventId,
          userSubject,
          activityId,
          productId,
          productName,
          unitPriceMinor,
          currency,
          quantity,
          totalPriceMinor,
          "UNPAID",
          1,
          null,
          unpaidDeadline,
          "PENDING",
          0,
          null,
          null,
          null);
    }

    OrderRecord withState(
        String nextStatus, long nextVersion, Long nextCancellationProjectionVersion) {
      return new OrderRecord(
          orderId,
          reservationId,
          transactionEventId,
          timeoutEventId,
          userSubject,
          activityId,
          productId,
          productName,
          unitPriceMinor,
          currency,
          quantity,
          totalPriceMinor,
          nextStatus,
          nextVersion,
          nextCancellationProjectionVersion,
          unpaidDeadline,
          timeoutDispatchState,
          timeoutDispatchAttempts,
          timeoutBrokerMessageId,
          timeoutDispatchedAt,
          timeoutDispatchError);
    }
  }
}
