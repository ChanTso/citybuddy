package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.merchant.MerchantChangeModels.Stored;
import io.citybuddy.commerce.merchant.MerchantChangeModels.View;
import io.citybuddy.commerce.merchant.MerchantModels.Context;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class MerchantChangeRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public MerchantChangeRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public Optional<Stored> find(String id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM merchant_price_draft WHERE draft_id = ?" + (lock ? " FOR UPDATE" : ""),
            this::stored,
            id)
        .stream()
        .findFirst();
  }

  public Optional<Stored> findByRequest(Context context, String key) {
    return jdbc
        .query(
            "SELECT * FROM merchant_price_draft WHERE operator_subject = ? AND session_id = ? AND request_key = ?",
            this::stored,
            context.operatorSubject(),
            context.sessionId(),
            key)
        .stream()
        .findFirst();
  }

  public List<View> list(Context context, String state, int limit, int offset) {
    List<Object> arguments = new ArrayList<>();
    arguments.add(context.operatorSubject());
    arguments.add(context.sessionId());
    String sql = "SELECT * FROM merchant_price_draft WHERE operator_subject = ? AND session_id = ?";
    if (state != null) {
      sql += " AND state = ?";
      arguments.add(state);
    }
    arguments.add(limit);
    arguments.add(offset);
    return jdbc
        .query(
            sql + " ORDER BY created_at DESC, draft_id LIMIT ? OFFSET ?",
            this::stored,
            arguments.toArray())
        .stream()
        .map(Stored::view)
        .toList();
  }

  public Stored insertOrReplay(
      String id,
      Context context,
      String key,
      String hash,
      String kind,
      String currency,
      JsonNode items,
      JsonNode payload,
      Instant now) {
    jdbc.update(
        """
        INSERT INTO merchant_price_draft
          (draft_id, operator_subject, session_id, request_key, intent_hash, kind,
           currency, state, items, payload, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'PREPARED', CAST(? AS JSON), CAST(? AS JSON), ?)
        ON DUPLICATE KEY UPDATE draft_id = draft_id
        """,
        id,
        context.operatorSubject(),
        context.sessionId(),
        key,
        hash,
        kind,
        currency,
        json(items),
        json(payload),
        Timestamp.from(now));
    return jdbc.queryForObject(
        """
        SELECT * FROM merchant_price_draft
        WHERE operator_subject = ? AND session_id = ? AND request_key = ? FOR UPDATE
        """,
        this::stored,
        context.operatorSubject(),
        context.sessionId(),
        key);
  }

  public View resolve(String id, String state, JsonNode result, Instant now) {
    int changed =
        jdbc.update(
            """
        UPDATE merchant_price_draft SET state = ?, result = CAST(? AS JSON), resolved_at = ?
        WHERE draft_id = ? AND state = 'PREPARED'
        """,
            state,
            json(result),
            Timestamp.from(now),
            id);
    if (changed != 1) {
      throw new IllegalStateException("Locked merchant change changed unexpectedly");
    }
    return find(id, true).orElseThrow().view();
  }

  private Stored stored(ResultSet row, int index) throws SQLException {
    try {
      String kind = row.getString("kind");
      String payload = row.getString("payload");
      JsonNode envelope = payload == null ? null : mapper.readTree(payload);
      JsonNode request = null;
      JsonNode snapshot = null;
      if (!kind.equals("PRICE_UPDATE")) {
        if (envelope == null
            || !envelope.isObject()
            || !envelope.has("request")
            || !envelope.get("request").isObject()
            || !envelope.has("operation")
            || !envelope.get("operation").isObject()) {
          throw new IllegalStateException("Stored merchant operation is invalid");
        }
        request = envelope.get("request");
        snapshot = envelope.get("operation");
      }
      JsonNode items = mapper.readTree(row.getString("items"));
      String encodedResult = row.getString("result");
      JsonNode result = encodedResult == null ? null : mapper.readTree(encodedResult);
      Timestamp resolved = row.getTimestamp("resolved_at");
      return new Stored(
          row.getString("operator_subject"),
          row.getString("session_id"),
          row.getString("intent_hash"),
          snapshot,
          new View(
              row.getString("draft_id"),
              kind,
              row.getString("state"),
              row.getString("currency"),
              items,
              request,
              result,
              row.getTimestamp("created_at").toInstant(),
              resolved == null ? null : resolved.toInstant()));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored merchant change is invalid", exception);
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Merchant change cannot be serialized", exception);
    }
  }
}
