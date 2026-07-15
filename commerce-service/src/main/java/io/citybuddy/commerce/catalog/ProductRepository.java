package io.citybuddy.commerce.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class ProductRepository {
  private static final String PUBLISHED_COLUMNS =
      "product_id, name, description, price_minor, currency, stock_quantity, available, publication_version";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public ProductRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  public long catalogGeneration() {
    Long generation =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(publication_generation), 0) FROM catalog_metadata", Long.class);
    if (generation == null) {
      throw new IllegalStateException("Catalog generation is missing");
    }
    return generation;
  }

  public List<Product> listPublished() {
    return jdbc.query(
        "SELECT "
            + PUBLISHED_COLUMNS
            + " FROM product WHERE publication_state = 'PUBLISHED' ORDER BY product_id",
        ProductRepository::mapProduct);
  }

  public Optional<Product> findPublished(String productId) {
    return jdbc
        .query(
            "SELECT "
                + PUBLISHED_COLUMNS
                + " FROM product WHERE product_id = ? AND publication_state = 'PUBLISHED'",
            ProductRepository::mapProduct,
            productId)
        .stream()
        .findFirst();
  }

  public Optional<LiveFields> findPublishedLiveFields(String productId) {
    return jdbc
        .query(
            """
            SELECT price_minor, currency, stock_quantity, available, publication_version
            FROM product
            WHERE product_id = ? AND publication_state = 'PUBLISHED'
            """,
            (result, row) ->
                new LiveFields(
                    result.getLong("price_minor"),
                    result.getString("currency"),
                    result.getLong("stock_quantity"),
                    result.getBoolean("available"),
                    result.getLong("publication_version")),
            productId)
        .stream()
        .findFirst();
  }

  public List<String> publishedIds() {
    return jdbc.queryForList(
        "SELECT product_id FROM product WHERE publication_state = 'PUBLISHED' ORDER BY product_id",
        String.class);
  }

  public Publication publish(ProductDraft draft, UUID eventId) {
    List<Long> existing =
        jdbc.queryForList(
            "SELECT publication_version FROM product WHERE product_id = ? FOR UPDATE",
            Long.class,
            draft.productId());
    long version = existing.isEmpty() ? 1 : Math.addExact(existing.getFirst(), 1);
    if (existing.isEmpty()) {
      jdbc.update(
          """
          INSERT INTO product
            (product_id, name, description, price_minor, currency, stock_quantity,
             available, publication_state, publication_version)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          draft.productId(),
          draft.name(),
          draft.description(),
          draft.priceMinor(),
          draft.currency(),
          draft.stockQuantity(),
          draft.available(),
          draft.published() ? "PUBLISHED" : "UNPUBLISHED",
          version);
    } else {
      jdbc.update(
          """
          UPDATE product
          SET name = ?, description = ?, price_minor = ?, currency = ?, stock_quantity = ?,
              available = ?, publication_state = ?, publication_version = ?
          WHERE product_id = ?
          """,
          draft.name(),
          draft.description(),
          draft.priceMinor(),
          draft.currency(),
          draft.stockQuantity(),
          draft.available(),
          draft.published() ? "PUBLISHED" : "UNPUBLISHED",
          version,
          draft.productId());
    }
    jdbc.update(
        """
        INSERT INTO catalog_metadata (singleton_id, publication_generation)
        VALUES (1, 1)
        ON DUPLICATE KEY UPDATE publication_generation = publication_generation + 1
        """);
    long generation = catalogGeneration();
    CatalogEvent event =
        new CatalogEvent(
            eventId.toString(),
            draft.productId(),
            version,
            generation,
            draft.published() ? "PUBLISHED" : "UNPUBLISHED");
    jdbc.update(
        """
        INSERT INTO commerce_outbox
          (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload)
        VALUES (?, 'PRODUCT', ?, ?, 'PRODUCT_PUBLICATION_CHANGED', CAST(? AS JSON))
        """,
        event.eventId(),
        event.productId(),
        event.productVersion(),
        eventJson(event));
    return new Publication(event, draft.published());
  }

  public List<OutboxEvent> pendingOutbox(int limit) {
    return jdbc.query(
        """
        SELECT event_id, payload
        FROM commerce_outbox
        WHERE publication_state = 'PENDING'
        ORDER BY created_at, event_id
        LIMIT ?
        """,
        (result, row) -> new OutboxEvent(result.getString("event_id"), result.getString("payload")),
        limit);
  }

  public void recordPublishFailure(String eventId) {
    jdbc.update(
        """
        UPDATE commerce_outbox
        SET publish_attempts = publish_attempts + 1
        WHERE event_id = ? AND publication_state = 'PENDING'
        """,
        eventId);
  }

  public void markPublished(String eventId) {
    int changed =
        jdbc.update(
            """
            UPDATE commerce_outbox
            SET publication_state = 'PUBLISHED', publish_attempts = publish_attempts + 1,
                published_at = CURRENT_TIMESTAMP(6)
            WHERE event_id = ? AND publication_state = 'PENDING'
            """,
            eventId);
    if (changed != 1) {
      throw new IllegalStateException("Outbox event is not pending: " + eventId);
    }
  }

  public CatalogEvent parseEvent(String payload) {
    try {
      return objectMapper.readValue(payload, CatalogEvent.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Malformed catalog event", exception);
    }
  }

  private String eventJson(CatalogEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Catalog event serialization failed", exception);
    }
  }

  private static Product mapProduct(ResultSet result, int row) throws SQLException {
    return new Product(
        result.getString("product_id"),
        result.getString("name"),
        result.getString("description"),
        result.getLong("price_minor"),
        result.getString("currency"),
        result.getLong("stock_quantity"),
        result.getBoolean("available"),
        result.getLong("publication_version"));
  }

  public record ProductDraft(
      String productId,
      String name,
      String description,
      long priceMinor,
      String currency,
      long stockQuantity,
      boolean available,
      boolean published) {}

  public record LiveFields(
      long priceMinor,
      String currency,
      long stockQuantity,
      boolean available,
      long publicationVersion) {}

  public record CatalogEvent(
      String eventId,
      String productId,
      long productVersion,
      long catalogGeneration,
      String publicationState) {}

  public record Publication(CatalogEvent event, boolean published) {}

  public record OutboxEvent(String eventId, String payload) {}
}
