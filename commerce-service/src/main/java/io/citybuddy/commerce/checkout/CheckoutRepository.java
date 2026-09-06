package io.citybuddy.commerce.checkout;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class CheckoutRepository {
  private static final String COLUMNS =
      "checkout_id, intent_hash, source_cart_version, currency, total_minor, created_at";
  private final JdbcTemplate jdbc;

  public CheckoutRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Receipt> findByKey(String owner, String key) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM shopping_checkout WHERE user_subject=? AND request_key=?",
            CheckoutRepository::receipt,
            owner,
            key)
        .stream()
        .findFirst();
  }

  public Optional<Receipt> find(String owner, String checkoutId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM shopping_checkout WHERE user_subject=? AND checkout_id=?",
            CheckoutRepository::receipt,
            owner,
            checkoutId)
        .stream()
        .findFirst();
  }

  public void insert(
      String id,
      String owner,
      String key,
      String hash,
      long cartVersion,
      String currency,
      long totalMinor) {
    jdbc.update(
        """
        INSERT INTO shopping_checkout
          (checkout_id,user_subject,request_key,intent_hash,source_cart_version,currency,total_minor)
        VALUES (?,?,?,?,?,?,?)
        """,
        id,
        owner,
        key,
        hash,
        cartVersion,
        currency,
        totalMinor);
  }

  public void attach(String id, int line, String orderId) {
    jdbc.update(
        "INSERT INTO shopping_checkout_order (checkout_id,line_no,order_id) VALUES (?,?,?)",
        id,
        line,
        orderId);
  }

  public List<String> orderIds(String id) {
    return jdbc.queryForList(
        "SELECT order_id FROM shopping_checkout_order WHERE checkout_id=? ORDER BY line_no",
        String.class,
        id);
  }

  private static Receipt receipt(ResultSet row, int index) throws SQLException {
    return new Receipt(
        row.getString("checkout_id"),
        row.getString("intent_hash"),
        row.getLong("source_cart_version"),
        row.getString("currency"),
        row.getLong("total_minor"),
        row.getTimestamp("created_at").toInstant());
  }

  public record Receipt(
      String checkoutId,
      String intentHash,
      long sourceCartVersion,
      String currency,
      long totalMinor,
      Instant createdAt) {}
}
