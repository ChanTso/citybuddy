package io.citybuddy.commerce.retail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.Configuration;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.EstimateItem;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.Rules;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public final class RetailFulfillmentRepository {
  private final JdbcTemplate jdbc;
  private final ObjectReader rulesReader;

  public RetailFulfillmentRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    ObjectMapper strict = mapper.copy();
    strict
        .coercionConfigFor(LogicalType.Integer)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
    rulesReader =
        strict
            .readerFor(Rules.class)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public Configuration configuration() {
    return jdbc
        .query(
            "SELECT config_version, currency, time_zone, rules FROM retail_fulfillment_config"
                + " WHERE config_id = 'default'",
            (row, index) -> {
              try {
                return new Configuration(
                    row.getLong("config_version"),
                    row.getString("currency"),
                    row.getString("time_zone"),
                    rulesReader.readValue(row.getString("rules")));
              } catch (JsonProcessingException
                  | IllegalArgumentException
                  | DateTimeException exception) {
                throw new IllegalStateException(
                    "Invalid stored retail fulfillment configuration", exception);
              }
            })
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("Retail fulfillment configuration is missing"));
  }

  public List<Sku> skus(List<EstimateItem> items) {
    if (items.isEmpty()) {
      return List.of();
    }
    List<String> requested = new ArrayList<>();
    List<Object> arguments = new ArrayList<>();
    for (int index = 0; index < items.size(); index++) {
      requested.add("SELECT ? AS requested_id, " + index + " AS item_index");
      arguments.add(items.get(index).productId());
    }
    return jdbc.query(
        "SELECT r.item_index, p.product_id, p.price_minor, p.currency, p.stock_quantity,"
            + " p.available, p.publication_state, p.publication_version,"
            + " JSON_UNQUOTE(JSON_EXTRACT(JSON_MERGE_PATCH(COALESCE(f.content, JSON_OBJECT()),"
            + " COALESCE(m.content, JSON_OBJECT())), '$.category')) AS category"
            + " FROM ("
            + String.join(" UNION ALL ", requested)
            + ") r"
            + " LEFT JOIN product p ON p.product_id = r.requested_id"
            + " LEFT JOIN retail_product_metadata m ON m.product_id = p.product_id"
            + " LEFT JOIN retail_product_family f ON f.family_id = m.family_id"
            + " ORDER BY r.item_index",
        (row, index) ->
            new Sku(
                row.getInt("item_index"),
                row.getString("product_id"),
                row.getLong("price_minor"),
                row.getString("currency"),
                row.getLong("stock_quantity"),
                row.getBoolean("available"),
                row.getString("publication_state"),
                row.getLong("publication_version"),
                row.getString("category")),
        arguments.toArray());
  }

  public record Sku(
      int itemIndex,
      String productId,
      long priceMinor,
      String currency,
      long stockQuantity,
      boolean available,
      String publicationState,
      long publicationVersion,
      String category) {}
}
