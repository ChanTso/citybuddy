package io.citybuddy.commerce.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
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
    return recordPublication(draft.productId(), version, draft.published(), eventId);
  }

  public List<PriceChangeResult> changePrices(List<PriceChange> changes, String currency) {
    List<PriceChange> ordered =
        changes.stream().sorted(Comparator.comparing(PriceChange::productId)).toList();
    List<PriceSnapshot> snapshots = new ArrayList<>();
    // Lock every product before taking catalog_metadata, also used by single-product publication.
    for (PriceChange change : ordered) {
      snapshots.add(
          jdbc
              .query(
                  """
                  SELECT price_minor, currency, available, publication_state, publication_version
                  FROM product WHERE product_id = ? FOR UPDATE
                  """,
                  (result, row) ->
                      new PriceSnapshot(
                          result.getLong("price_minor"),
                          result.getString("currency"),
                          result.getBoolean("available"),
                          result.getString("publication_state"),
                          result.getLong("publication_version")),
                  change.productId())
              .stream()
              .findFirst()
              .orElseThrow(() -> rejected(ProductPriceChangeException.Reason.NOT_FOUND, change)));
    }
    List<Long> nextVersions = new ArrayList<>();
    // Business rejections must precede writes so the caller can commit a rejected approval receipt.
    for (int index = 0; index < ordered.size(); index++) {
      PriceChange change = ordered.get(index);
      PriceSnapshot current = snapshots.get(index);
      if (current.version() != change.expectedVersion()) {
        throw rejected(ProductPriceChangeException.Reason.VERSION_CONFLICT, change);
      }
      if (!"PUBLISHED".equals(current.publicationState()) || !current.available()) {
        throw rejected(ProductPriceChangeException.Reason.NOT_ORDERABLE, change);
      }
      if (!currency.equals(current.currency())) {
        throw rejected(ProductPriceChangeException.Reason.CURRENCY_MISMATCH, change);
      }
      // READ_COMMITTED sees completed activity creation without reversing activity/product locks.
      if (!jdbc.queryForList(
              "SELECT activity_id FROM seckill_activity WHERE product_id = ? LIMIT 1",
              String.class,
              change.productId())
          .isEmpty()) {
        throw rejected(ProductPriceChangeException.Reason.SECKILL_PRODUCT, change);
      }
      nextVersions.add(Math.addExact(current.version(), 1));
    }
    List<PriceChangeResult> results = new ArrayList<>();
    for (int index = 0; index < ordered.size(); index++) {
      PriceChange change = ordered.get(index);
      PriceSnapshot current = snapshots.get(index);
      long nextVersion = nextVersions.get(index);
      int changed =
          jdbc.update(
              """
              UPDATE product SET price_minor = ?, publication_version = ?
              WHERE product_id = ? AND publication_version = ?
              """,
              change.newPriceMinor(),
              nextVersion,
              change.productId(),
              current.version());
      if (changed != 1) {
        throw new IllegalStateException("Product changed during its locked price update");
      }
      Publication publication =
          recordPublication(change.productId(), nextVersion, true, UUID.randomUUID());
      results.add(
          new PriceChangeResult(
              change.productId(),
              current.priceMinor(),
              change.newPriceMinor(),
              currency,
              current.version(),
              nextVersion,
              publication.event().eventId()));
    }
    return List.copyOf(results);
  }

  private static ProductPriceChangeException rejected(
      ProductPriceChangeException.Reason reason, PriceChange change) {
    return new ProductPriceChangeException(reason, change.productId());
  }

  private Publication recordPublication(
      String productId, long version, boolean published, UUID eventId) {
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
            productId,
            version,
            generation,
            published ? "PUBLISHED" : "UNPUBLISHED");
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
    return new Publication(event, published);
  }

  public List<OutboxEvent> pendingOutbox(int limit) {
    return jdbc.query(
        """
        SELECT event_id, payload
        FROM commerce_outbox
        WHERE publication_state = 'PENDING'
          AND event_type = 'PRODUCT_PUBLICATION_CHANGED'
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

  public record PriceChange(String productId, long expectedVersion, long newPriceMinor) {}

  public record PriceChangeResult(
      String productId,
      long oldPriceMinor,
      long newPriceMinor,
      String currency,
      long oldVersion,
      long newVersion,
      String eventId) {}

  private record PriceSnapshot(
      long priceMinor, String currency, boolean available, String publicationState, long version) {}

  public record CatalogEvent(
      String eventId,
      String productId,
      long productVersion,
      long catalogGeneration,
      String publicationState) {}

  public record Publication(CatalogEvent event, boolean published) {}

  public record OutboxEvent(String eventId, String payload) {}
}
