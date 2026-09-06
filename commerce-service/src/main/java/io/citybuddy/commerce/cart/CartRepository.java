package io.citybuddy.commerce.cart;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.cart.CartModels.Receipt;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public class CartRepository {
  private static final TypeReference<Map<String, String>> OPTION_VALUES = new TypeReference<>() {};
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public CartRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public long lockCart(String owner) {
    jdbc.update(
        """
        INSERT INTO shopping_cart (user_subject, cart_version) VALUES (?, 0)
        ON DUPLICATE KEY UPDATE user_subject = user_subject
        """,
        owner);
    return jdbc.queryForObject(
        "SELECT cart_version FROM shopping_cart WHERE user_subject = ? FOR UPDATE",
        Long.class,
        owner);
  }

  public long version(String owner) {
    return jdbc
        .query(
            "SELECT cart_version FROM shopping_cart WHERE user_subject = ?",
            (row, index) -> row.getLong("cart_version"),
            owner)
        .stream()
        .findFirst()
        .orElse(0L);
  }

  public List<Line> lines(String owner) {
    return jdbc.query(
        """
        SELECT product_id, quantity FROM shopping_cart_item
        WHERE user_subject = ? ORDER BY product_id
        """,
        (row, index) -> new Line(row.getString("product_id"), row.getInt("quantity")),
        owner);
  }

  public List<CurrentLine> currentLines(String owner) {
    return jdbc.query(
        """
        SELECT i.product_id AS line_product_id, i.quantity,
               p.product_id, p.name, p.price_minor, p.currency, p.publication_version,
               p.stock_quantity, p.available, p.publication_state, m.family_id,
               COALESCE(m.option_values, JSON_OBJECT()) AS option_values,
               JSON_MERGE_PATCH(COALESCE(f.content, JSON_OBJECT()),
                                COALESCE(m.content, JSON_OBJECT())) AS content
        FROM shopping_cart_item i JOIN product p ON p.product_id = i.product_id
        LEFT JOIN retail_product_metadata m ON m.product_id = p.product_id
        LEFT JOIN retail_product_family f ON f.family_id = m.family_id
        WHERE i.user_subject = ? ORDER BY i.product_id
        """,
        (row, index) -> {
          try {
            var image = mapper.readTree(row.getString("content")).get("imageUrl");
            return new CurrentLine(
                row.getString("line_product_id"),
                row.getInt("quantity"),
                product(row),
                image == null || image.isNull() ? null : image.textValue(),
                mapper.readValue(row.getString("option_values"), OPTION_VALUES),
                row.getString("family_id"));
          } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored cart product metadata is invalid", exception);
          }
        },
        owner);
  }

  public Optional<Product> findProduct(String productId, boolean lock) {
    return jdbc
        .query(
            """
            SELECT product_id, name, price_minor, currency, publication_version,
                   stock_quantity, available, publication_state
            FROM product WHERE product_id = ?
            """
                + (lock ? " FOR SHARE" : ""),
            (row, index) -> product(row),
            productId)
        .stream()
        .findFirst();
  }

  public Optional<StoredCommand> findCommand(String owner, String key) {
    return jdbc
        .query(
            """
            SELECT command_key, intent_hash, operation, product_id, before_quantity,
                   after_quantity, applied_cart_version
            FROM shopping_cart_command WHERE user_subject = ? AND command_key = ?
            """,
            (row, index) ->
                new StoredCommand(
                    row.getString("intent_hash"),
                    new Receipt(
                        row.getString("command_key"),
                        row.getString("operation"),
                        row.getString("product_id"),
                        row.getInt("before_quantity"),
                        row.getInt("after_quantity"),
                        row.getLong("applied_cart_version"))),
            owner,
            key)
        .stream()
        .findFirst();
  }

  public void saveQuantity(String owner, String productId, int quantity) {
    jdbc.update(
        """
        INSERT INTO shopping_cart_item (user_subject, product_id, quantity) VALUES (?, ?, ?)
        ON DUPLICATE KEY UPDATE quantity = ?
        """,
        owner,
        productId,
        quantity,
        quantity);
  }

  public void remove(String owner, String productId) {
    jdbc.update(
        "DELETE FROM shopping_cart_item WHERE user_subject = ? AND product_id = ?",
        owner,
        productId);
  }

  public long advance(String owner) {
    long next =
        Math.incrementExact(
            jdbc.queryForObject(
                "SELECT cart_version FROM shopping_cart WHERE user_subject = ? FOR UPDATE",
                Long.class,
                owner));
    if (jdbc.update(
            "UPDATE shopping_cart SET cart_version = ?, updated_at = CURRENT_TIMESTAMP(6) "
                + "WHERE user_subject = ?",
            next,
            owner)
        != 1) {
      throw new IllegalStateException("Locked cart root is missing");
    }
    return next;
  }

  public long clearAndAdvance(String owner) {
    jdbc.update("DELETE FROM shopping_cart_item WHERE user_subject = ?", owner);
    return advance(owner);
  }

  public void insertCommand(String owner, String hash, Receipt receipt) {
    jdbc.update(
        """
        INSERT INTO shopping_cart_command
          (user_subject, command_key, intent_hash, operation, product_id, before_quantity,
           after_quantity, applied_cart_version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        owner,
        receipt.key(),
        hash,
        receipt.operation(),
        receipt.productId(),
        receipt.beforeQuantity(),
        receipt.afterQuantity(),
        receipt.appliedVersion());
  }

  private static Product product(java.sql.ResultSet row) throws java.sql.SQLException {
    return new Product(
        row.getString("product_id"),
        row.getString("name"),
        row.getLong("price_minor"),
        row.getString("currency"),
        row.getLong("publication_version"),
        row.getLong("stock_quantity"),
        row.getBoolean("available"),
        row.getString("publication_state"));
  }

  public record Line(String productId, int quantity) {}

  public record Product(
      String productId,
      String name,
      long priceMinor,
      String currency,
      long productVersion,
      long stockQuantity,
      boolean available,
      String publicationState) {}

  public record CurrentLine(
      String productId,
      int quantity,
      Product product,
      String imageUrl,
      Map<String, String> optionValues,
      String familyId) {}

  public record StoredCommand(String intentHash, Receipt receipt) {}
}
