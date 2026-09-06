package io.citybuddy.commerce.shopping;

import io.citybuddy.commerce.shopping.ShoppingOrderModels.OrderView;
import io.citybuddy.commerce.shopping.ShoppingOrderModels.PaymentFacts;
import io.citybuddy.commerce.shopping.ShoppingOrderModels.ProductSnapshot;
import io.citybuddy.commerce.shopping.ShoppingOrderModels.RefundFacts;
import io.citybuddy.commerce.shopping.ShoppingOrderModels.RefundStateTotals;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

public final class ShoppingOrderRepository {
  private static final String STANDARD =
      """
      SELECT 'STANDARD' AS order_kind, order_id, user_subject, product_id, product_name,
             unit_price_minor, currency, quantity, total_price_minor, product_version,
             status, state_version, created_at, NULL AS unpaid_deadline
      FROM standard_order
      WHERE user_subject = ? AND BINARY user_subject = BINARY ? AND sandbox_id IS NULL
      """;
  private static final String SECKILL =
      """
      SELECT 'SECKILL' AS order_kind, order_id, user_subject, product_id, product_name,
             unit_price_minor, currency, quantity, total_price_minor, NULL AS product_version,
             status, state_version, created_at, unpaid_deadline
      FROM seckill_order
      WHERE user_subject = ? AND BINARY user_subject = BINARY ?
      """;
  private static final String PAYMENT_JOIN =
      """
      SELECT o.*, p.attempt_id, p.state AS payment_state, p.state_version AS payment_version,
             p.amount_minor, p.refunded_amount_minor, p.currency AS payment_currency,
             p.succeeded_at
      FROM page o
      LEFT JOIN mock_payment_attempt p
        ON p.order_id = o.order_id AND p.order_kind = o.order_kind
       AND BINARY p.user_subject = BINARY o.user_subject AND p.sandbox_id IS NULL
      ORDER BY o.created_at DESC, o.order_id DESC, o.order_kind
      """;
  private final JdbcTemplate jdbc;
  private final OrderFulfillmentRepository fulfillment;

  public ShoppingOrderRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.fulfillment = new OrderFulfillmentRepository(jdbc);
  }

  public List<OrderView> list(String owner, int limit) {
    List<OrderView> orders =
        jdbc.query(
            "WITH candidates AS (("
                + STANDARD
                + " ORDER BY created_at DESC, order_id DESC LIMIT ?) UNION ALL ("
                + SECKILL
                + " ORDER BY created_at DESC, order_id DESC LIMIT ?)),"
                + " page AS (SELECT * FROM candidates"
                + " ORDER BY created_at DESC, order_id DESC, order_kind LIMIT ?) "
                + PAYMENT_JOIN,
            ShoppingOrderRepository::order,
            owner,
            owner,
            limit,
            owner,
            owner,
            limit,
            limit);
    return withFulfillment(withRefunds(orders));
  }

  public Optional<OrderView> find(String owner, String orderId) {
    List<OrderView> orders =
        jdbc.query(
            "WITH page AS ("
                + STANDARD
                + " AND order_id = ? UNION ALL "
                + SECKILL
                + " AND order_id = ?) "
                + PAYMENT_JOIN,
            ShoppingOrderRepository::order,
            owner,
            owner,
            orderId,
            owner,
            owner,
            orderId);
    if (orders.size() > 1) {
      throw new IllegalStateException("Shopping order identifier is ambiguous");
    }
    return withFulfillment(withRefunds(orders)).stream().findFirst();
  }

  public List<OrderView> findStandardOrders(String owner, List<String> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    List<Object> parameters = new ArrayList<>();
    parameters.add(owner);
    parameters.add(owner);
    parameters.addAll(ids);
    return withFulfillment(
        withRefunds(
            jdbc.query(
                "WITH page AS ("
                    + STANDARD
                    + " AND order_id IN ("
                    + placeholders
                    + ")) "
                    + PAYMENT_JOIN,
                ShoppingOrderRepository::order,
                parameters.toArray())));
  }

  private List<OrderView> withFulfillment(List<OrderView> orders) {
    var delivery =
        fulfillment.find(
            orders.stream()
                .filter(order -> order.orderKind().equals("STANDARD"))
                .map(OrderView::orderId)
                .toList());
    return orders.stream()
        .map(
            order ->
                new OrderView(
                    order.orderKind(),
                    order.orderId(),
                    order.status(),
                    order.stateVersion(),
                    order.createdAt(),
                    order.unpaidDeadline(),
                    order.product(),
                    order.payment(),
                    order.refunds(),
                    order.orderKind().equals("STANDARD") ? delivery.get(order.orderId()) : null))
        .toList();
  }

  private List<OrderView> withRefunds(List<OrderView> orders) {
    List<String> attempts =
        orders.stream()
            .filter(order -> order.payment() != null)
            .map(order -> order.payment().attemptId())
            .toList();
    if (attempts.isEmpty()) {
      return orders;
    }
    String placeholders = attempts.stream().map(id -> "?").collect(Collectors.joining(","));
    Map<String, List<RefundStateTotals>> totals = new HashMap<>();
    jdbc.query(
        """
        SELECT r.payment_attempt_id, r.state, COUNT(*) AS refund_count,
               SUM(r.requested_amount_minor) AS requested_amount_minor,
               SUM(r.refunded_amount_minor) AS refunded_amount_minor
        FROM mock_refund r JOIN mock_payment_attempt p ON p.attempt_id = r.payment_attempt_id
        WHERE p.attempt_id IN (%s) AND p.sandbox_id IS NULL
          AND BINARY r.user_subject = BINARY p.user_subject
          AND r.order_kind = p.order_kind AND r.order_id = p.order_id
          AND r.currency = p.currency
        GROUP BY r.payment_attempt_id, r.state ORDER BY r.payment_attempt_id, r.state
        """
            .formatted(placeholders),
        row -> {
          totals
              .computeIfAbsent(row.getString("payment_attempt_id"), key -> new ArrayList<>())
              .add(
                  new RefundStateTotals(
                      row.getString("state"),
                      row.getLong("refund_count"),
                      row.getBigDecimal("requested_amount_minor").longValueExact(),
                      row.getBigDecimal("refunded_amount_minor").longValueExact()));
        },
        attempts.toArray());
    return orders.stream()
        .map(
            order -> {
              List<RefundStateTotals> byState =
                  order.payment() == null
                      ? List.of()
                      : List.copyOf(totals.getOrDefault(order.payment().attemptId(), List.of()));
              long reserved = 0;
              for (RefundStateTotals state : byState) {
                if (!state.state().equals("FAILED")) {
                  reserved = Math.addExact(reserved, state.requestedAmountMinor());
                }
              }
              return new OrderView(
                  order.orderKind(),
                  order.orderId(),
                  order.status(),
                  order.stateVersion(),
                  order.createdAt(),
                  order.unpaidDeadline(),
                  order.product(),
                  order.payment(),
                  new RefundFacts(reserved, byState),
                  order.fulfillment());
            })
        .toList();
  }

  private static OrderView order(ResultSet row, int index) throws SQLException {
    PaymentFacts payment =
        row.getString("attempt_id") == null
            ? null
            : new PaymentFacts(
                row.getString("attempt_id"),
                row.getString("payment_state"),
                row.getLong("payment_version"),
                row.getLong("amount_minor"),
                row.getLong("refunded_amount_minor"),
                row.getString("payment_currency"),
                instant(row, "succeeded_at"));
    ProductSnapshot product =
        new ProductSnapshot(
            row.getString("product_id"),
            row.getString("product_name"),
            row.getLong("unit_price_minor"),
            row.getString("currency"),
            row.getLong("quantity"),
            row.getLong("total_price_minor"),
            row.getObject("product_version", Long.class));
    String status = row.getString("status");
    if (status.equals("PAID") != (payment != null && payment.state().equals("SUCCEEDED"))
        || (payment != null
            && (payment.amountMinor() != product.totalPriceMinor()
                || !payment.currency().equals(product.currency())))) {
      throw new IllegalStateException("Shopping order and payment facts conflict");
    }
    return new OrderView(
        row.getString("order_kind"),
        row.getString("order_id"),
        status,
        row.getLong("state_version"),
        instant(row, "created_at"),
        instant(row, "unpaid_deadline"),
        product,
        payment,
        new RefundFacts(0, List.of()),
        null);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    Timestamp value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
