package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.merchant.MerchantModels.Context;
import io.citybuddy.commerce.merchant.MerchantModels.DraftItem;
import io.citybuddy.commerce.merchant.MerchantModels.DraftView;
import io.citybuddy.commerce.merchant.MerchantModels.ProductView;
import io.citybuddy.commerce.merchant.MerchantModels.StoredDraft;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class MerchantRepository {
  private static final TypeReference<List<DraftItem>> ITEMS = new TypeReference<>() {};
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public MerchantRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public List<ProductView> products() {
    return jdbc.query(
        "SELECT * FROM merchant_products ORDER BY product_id LIMIT 100",
        MerchantRepository::product);
  }

  public Optional<ProductView> product(String productId) {
    return jdbc
        .query(
            "SELECT * FROM merchant_products WHERE product_id = ?",
            MerchantRepository::product,
            productId)
        .stream()
        .findFirst();
  }

  public List<Map<String, Object>> summary(Instant start, Instant end) {
    return jdbc.queryForList(
        """
        SELECT currency, COUNT(*) AS orderCount, COALESCE(SUM(quantity), 0) AS units,
               COALESCE(SUM(total_price_minor), 0) AS amountMinor
        FROM merchant_paid_orders
        WHERE succeeded_at >= ? AND succeeded_at < ?
        GROUP BY currency ORDER BY currency
        """,
        Timestamp.from(start),
        Timestamp.from(end));
  }

  public StoredDraft insertOrReplay(
      String id,
      Context context,
      String key,
      String intentHash,
      String currency,
      List<DraftItem> items,
      Instant now) {
    jdbc.update(
        """
        INSERT INTO merchant_price_draft
          (draft_id, operator_subject, session_id, request_key, intent_hash, currency,
           state, items, created_at)
        VALUES (?, ?, ?, ?, ?, ?, 'PREPARED', CAST(? AS JSON), ?)
        ON DUPLICATE KEY UPDATE draft_id = draft_id
        """,
        id,
        context.operatorSubject(),
        context.sessionId(),
        key,
        intentHash,
        currency,
        json(items),
        Timestamp.from(now));
    return jdbc.queryForObject(
        """
        SELECT * FROM merchant_price_draft
        WHERE operator_subject = ? AND session_id = ? AND request_key = ? FOR UPDATE
        """,
        this::draft,
        context.operatorSubject(),
        context.sessionId(),
        key);
  }

  public Optional<StoredDraft> find(String id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM merchant_price_draft WHERE draft_id = ?" + (lock ? " FOR UPDATE" : ""),
            this::draft,
            id)
        .stream()
        .findFirst();
  }

  public Optional<StoredDraft> findByRequest(Context context, String key) {
    return jdbc
        .query(
            "SELECT * FROM merchant_price_draft WHERE operator_subject = ? AND session_id = ? AND request_key = ?",
            this::draft,
            context.operatorSubject(),
            context.sessionId(),
            key)
        .stream()
        .findFirst();
  }

  public DraftView resolve(String id, String state, JsonNode result, Instant now) {
    int count =
        jdbc.update(
            """
            UPDATE merchant_price_draft SET state = ?, result = CAST(? AS JSON), resolved_at = ?
            WHERE draft_id = ? AND state = 'PREPARED'
            """,
            state,
            json(result),
            Timestamp.from(now),
            id);
    if (count != 1) {
      throw new IllegalStateException("Locked merchant draft changed unexpectedly");
    }
    return find(id, true).orElseThrow().view();
  }

  private StoredDraft draft(ResultSet row, int index) throws SQLException {
    try {
      String result = row.getString("result");
      Timestamp resolved = row.getTimestamp("resolved_at");
      return new StoredDraft(
          row.getString("operator_subject"),
          row.getString("session_id"),
          row.getString("intent_hash"),
          new DraftView(
              row.getString("draft_id"),
              row.getString("currency"),
              row.getString("state"),
              List.copyOf(mapper.readValue(row.getString("items"), ITEMS)),
              result == null ? null : mapper.readTree(result),
              row.getTimestamp("created_at").toInstant(),
              resolved == null ? null : resolved.toInstant()));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored merchant draft is invalid", exception);
    }
  }

  private static ProductView product(ResultSet row, int index) throws SQLException {
    return new ProductView(
        row.getString("product_id"),
        row.getString("name"),
        row.getLong("price_minor"),
        row.getString("currency"),
        row.getLong("publication_version"),
        row.getLong("stock_quantity"),
        row.getBoolean("available"),
        row.getString("publication_state"),
        row.getBoolean("price_editable"));
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Merchant value cannot be serialized", exception);
    }
  }
}
