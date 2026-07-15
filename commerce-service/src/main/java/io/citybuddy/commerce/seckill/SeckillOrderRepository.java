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

  private Optional<OrderRecord> query(String sql, Object... arguments) {
    List<OrderRecord> rows =
        jdbc.query(
            sql,
            (result, row) ->
                new OrderRecord(
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
                    result.getTimestamp("unpaid_deadline").toInstant()),
            arguments);
    if (rows.size() > 1) {
      throw new IllegalStateException("Seckill order uniqueness is corrupted");
    }
    return rows.stream().findFirst();
  }

  private static String columns() {
    return "order_id, reservation_id, transaction_event_id, timeout_event_id, user_subject, "
        + "activity_id, product_id, product_name, unit_price_minor, currency, quantity, "
        + "total_price_minor, unpaid_deadline";
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
      Instant unpaidDeadline) {}
}
