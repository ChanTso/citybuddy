package io.citybuddy.commerce.shopping;

import io.citybuddy.commerce.shopping.ShoppingOrderModels.FulfillmentFacts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

public final class OrderFulfillmentRepository {
  private final JdbcTemplate jdbc;

  public OrderFulfillmentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // Callers pass the bounded order page after owner or merchant authorization.
  public Map<String, FulfillmentFacts> find(List<String> orderIds) {
    if (orderIds.isEmpty()) {
      return Map.of();
    }
    Map<String, FulfillmentFacts> result = new LinkedHashMap<>();
    String placeholders = orderIds.stream().map(id -> "?").collect(Collectors.joining(","));
    jdbc.query(
        """
        SELECT f.*, o.status AS order_status, o.total_price_minor, o.currency AS order_currency,
               p.state AS payment_state, p.amount_minor, p.currency AS payment_currency
        FROM retail_order_fulfillment f
        JOIN standard_order o ON o.order_id = f.order_id AND o.sandbox_id IS NULL
        LEFT JOIN mock_payment_attempt p
          ON p.order_kind = 'STANDARD' AND p.order_id = o.order_id AND p.sandbox_id IS NULL
         AND BINARY p.user_subject = BINARY o.user_subject
        WHERE f.order_id IN (%s)
        """
            .formatted(placeholders),
        row -> {
          if (!"PAID".equals(row.getString("order_status"))
              || !"SUCCEEDED".equals(row.getString("payment_state"))
              || row.getLong("total_price_minor") != row.getLong("amount_minor")
              || !row.getString("order_currency").equals(row.getString("payment_currency"))) {
            throw new IllegalStateException("Order fulfillment conflicts with payment truth");
          }
          result.put(row.getString("order_id"), facts(row));
        },
        orderIds.toArray());
    return Map.copyOf(result);
  }

  private static FulfillmentFacts facts(ResultSet row) throws SQLException {
    return new FulfillmentFacts(
        row.getString("method"),
        row.getString("stage"),
        instant(row, "promised_delivery_at"),
        instant(row, "estimated_delivery_at"),
        instant(row, "packed_at"),
        instant(row, "shipped_at"),
        instant(row, "delivered_at"),
        row.getString("delay_reason"),
        row.getString("source_kind"),
        row.getString("source_ref"),
        instant(row, "observed_at"));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    Timestamp value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
