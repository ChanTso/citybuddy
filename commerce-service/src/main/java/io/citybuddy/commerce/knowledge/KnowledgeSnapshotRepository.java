package io.citybuddy.commerce.knowledge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public final class KnowledgeSnapshotRepository {
  private final JdbcTemplate jdbc;

  public KnowledgeSnapshotRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<PublishedSource> publishedFaqs(int limit) {
    return jdbc.query(
        """
        SELECT s.faq_id, s.published_version, s.published_question, s.published_answer,
               c.event_id, c.occurred_at
        FROM faq_source s
        LEFT JOIN faq_publication_command c
          ON c.faq_id = s.faq_id AND c.source_version = s.published_version
        WHERE s.published_version > 0
        ORDER BY s.faq_id
        LIMIT ?
        """,
        (result, row) ->
            new PublishedSource(
                result.getString("event_id"),
                result.getString("faq_id"),
                result.getLong("published_version"),
                "answer",
                "faq",
                timestamp(result, "occurred_at"),
                result.getString("published_question"),
                result.getString("published_answer"),
                "faq",
                "und"),
        limit);
  }

  public List<PublishedSource> publishedProducts(int limit) {
    return jdbc.query(
        """
        SELECT p.product_id, p.publication_version, p.name, p.description,
               o.event_id, o.created_at
        FROM product p
        LEFT JOIN commerce_outbox o
          ON o.aggregate_type = 'PRODUCT'
         AND o.aggregate_id = p.product_id
         AND o.aggregate_version = p.publication_version
         AND o.event_type = 'PRODUCT_PUBLICATION_CHANGED'
        WHERE p.publication_state = 'PUBLISHED'
        ORDER BY p.product_id
        LIMIT ?
        """,
        (result, row) -> mapProduct(result),
        limit);
  }

  private static PublishedSource mapProduct(ResultSet result) throws SQLException {
    String productId = result.getString("product_id");
    return new PublishedSource(
        result.getString("event_id"),
        productId,
        result.getLong("publication_version"),
        "description",
        "product",
        timestamp(result, "created_at"),
        result.getString("name"),
        result.getString("description"),
        "product",
        "und");
  }

  private static Instant timestamp(ResultSet result, String column) throws SQLException {
    var value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  public record PublishedSource(
      String eventId,
      String sourceId,
      long sourceVersion,
      String chunkId,
      String docType,
      Instant occurredTime,
      String title,
      String content,
      String category,
      String language) {}
}
