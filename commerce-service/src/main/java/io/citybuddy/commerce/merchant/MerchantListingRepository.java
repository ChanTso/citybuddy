package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.citybuddy.commerce.merchant.MerchantListingModels.InventoryAlert;
import io.citybuddy.commerce.merchant.MerchantListingModels.Listing;
import io.citybuddy.commerce.merchant.MerchantListingModels.Operations;
import io.citybuddy.commerce.merchant.MerchantListingModels.Sales;
import io.citybuddy.commerce.merchant.MerchantListingModels.Search;
import io.citybuddy.commerce.merchant.MerchantListingModels.Window;
import io.citybuddy.commerce.retail.RetailCatalogModels.Option;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

public final class MerchantListingRepository {
  private static final TypeReference<List<Option>> OPTIONS = new TypeReference<>() {};
  private static final TypeReference<Map<String, String>> VALUES = new TypeReference<>() {};
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
  private static final String FACTS =
      """
      WITH sales AS (
        SELECT paid.product_id, COUNT(*) AS order_count, SUM(paid.quantity) AS units,
          SUM(CASE WHEN EXISTS (
            SELECT 1 FROM mock_refund r JOIN mock_payment_attempt payment
              ON payment.attempt_id = r.payment_attempt_id
              AND payment.order_kind = paid.order_kind AND payment.order_id = paid.order_id
              AND payment.state = 'SUCCEEDED' AND payment.sandbox_id IS NULL
              AND payment.amount_minor = paid.total_price_minor AND payment.currency = paid.currency
              AND BINARY payment.user_subject = BINARY r.user_subject
            WHERE r.order_kind = paid.order_kind AND r.order_id = paid.order_id
              AND r.currency = paid.currency AND r.state IN ('REQUESTED','PROCESSING','SUCCEEDED')
              AND r.created_at < ?
          ) THEN 1 ELSE 0 END) AS refund_order_count
        FROM merchant_paid_orders paid WHERE succeeded_at >= ? AND succeeded_at < ?
        GROUP BY paid.product_id
      ), leaves AS (
        SELECT p.product_id,p.name,p.description,p.price_minor,p.currency,p.stock_quantity,
          p.available,p.publication_state,p.publication_version,
          m.family_id,COALESCE(m.metadata_version,0) AS metadata_version,
          COALESCE(m.display_order,0) AS display_order,
          COALESCE(m.option_values,JSON_OBJECT()) AS option_values,
          f.name AS family_name,f.description AS family_description,
          f.metadata_version AS family_metadata_version,
          COALESCE(f.content,JSON_OBJECT()) AS family_content,
          COALESCE(f.content,m.content,JSON_OBJECT()) AS summary_content,
          COALESCE(f.options,JSON_ARRAY()) AS family_options,
          JSON_MERGE_PATCH(COALESCE(f.content,JSON_OBJECT()),
                           COALESCE(m.content,JSON_OBJECT())) AS content,
          COALESCE(m.family_id,p.product_id) AS root_id,
          COALESCE(f.display_order,m.display_order,0) AS root_order,
          ops.unit_cost_minor,ops.low_stock_threshold,ops.content_quality,
          ops.missing_attributes,ops.facts_version,ops.observed_at,ops.source_ref,
          COALESCE(sales.order_count,0) AS order_count,COALESCE(sales.units,0) AS units,
          COALESCE(sales.refund_order_count,0) AS refund_order_count,
          (p.publication_state='PUBLISHED' AND p.available
            AND NOT EXISTS (SELECT 1 FROM seckill_activity a WHERE a.product_id=p.product_id))
            AS price_editable
        FROM product p
        LEFT JOIN retail_product_metadata m ON m.product_id=p.product_id
        LEFT JOIN retail_product_family f ON f.family_id=m.family_id
        LEFT JOIN retail_product_operations ops ON ops.product_id=p.product_id
        LEFT JOIN sales ON sales.product_id=p.product_id
      )
      """;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public MerchantListingRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public List<Listing> search(Search search, Window window) {
    List<Object> arguments = arguments(window);
    String score = score(search.query(), arguments);
    StringBuilder sql =
        new StringBuilder(FACTS)
            .append(", scored AS (SELECT leaves.*, ")
            .append(score)
            .append(" AS score FROM leaves), roots AS (")
            .append("SELECT root_id,MIN(root_order) AS root_order,MAX(score) AS score,")
            .append("MIN(price_minor) AS price_minor,SUM(stock_quantity) AS stock_quantity,")
            .append("SUM(units) AS units,MIN(currency) AS currency,MAX(currency) AS max_currency,")
            .append("MAX(JSON_UNQUOTE(JSON_EXTRACT(summary_content,'$.category'))) AS category,")
            .append(
                "CASE WHEN SUM(publication_state='PUBLISHED' AND available AND stock_quantity>0)>0 THEN 'active'")
            .append(" WHEN SUM(publication_state='PUBLISHED' AND available)>0 THEN 'out_of_stock'")
            .append(
                " WHEN SUM(publication_state<>'DRAFT')=0 THEN 'draft' ELSE 'paused' END AS status,")
            .append("CASE WHEN SUM(content_quality='needs_work')>0 THEN 'needs_work'")
            .append(
                " WHEN COUNT(content_quality)=COUNT(*) THEN 'good' ELSE NULL END AS content_quality")
            .append(" FROM scored GROUP BY root_id) SELECT root_id FROM roots WHERE 1=1");
    if (search.query() != null) {
      sql.append(" AND score>0");
    }
    filter(sql, arguments, "status", search.status());
    filter(sql, arguments, "category", search.category());
    filter(sql, arguments, "content_quality", search.contentQuality());
    if (search.currency() != null) {
      sql.append(" AND currency=? AND max_currency=?");
      arguments.add(search.currency());
      arguments.add(search.currency());
    }
    if (search.maxStock() != null) {
      sql.append(" AND stock_quantity<=?");
      arguments.add(search.maxStock());
    }
    sql.append(" ORDER BY ")
        .append(
            switch (search.sort()) {
              case "stock_asc" -> "stock_quantity ASC";
              case "price_asc" -> "price_minor ASC";
              case "price_desc" -> "price_minor DESC";
              case "sales_desc" -> "units DESC";
              default -> "score DESC";
            })
        .append(",root_order,root_id LIMIT ? OFFSET ?");
    arguments.add(search.limit() + 1);
    arguments.add(search.offset());
    List<String> ids =
        jdbc.query(sql.toString(), (row, index) -> row.getString("root_id"), arguments.toArray());
    if (ids.isEmpty()) {
      return List.of();
    }
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    List<Object> detailArguments = arguments(window);
    detailArguments.addAll(ids);
    List<Leaf> leaves =
        jdbc.query(
            FACTS
                + " SELECT * FROM leaves WHERE root_id IN ("
                + placeholders
                + ")"
                + " ORDER BY root_order,display_order,product_id",
            this::leaf,
            detailArguments.toArray());
    Map<String, List<Leaf>> roots = new LinkedHashMap<>();
    for (Leaf leaf : leaves) {
      roots.computeIfAbsent(leaf.rootId(), ignored -> new ArrayList<>()).add(leaf);
    }
    return ids.stream().map(id -> listing(id, roots.get(id), window, false)).toList();
  }

  public Optional<Listing> find(String requested, Window window) {
    List<Object> arguments = arguments(window);
    arguments.add(requested);
    arguments.add(requested);
    arguments.add(requested);
    List<Lookup> matches =
        jdbc.query(
            FACTS
                + " SELECT leaves.*,CASE WHEN product_id=? THEN product_id ELSE family_id END AS lookup_id"
                + " FROM leaves WHERE product_id=? OR family_id=? ORDER BY display_order,product_id",
            (row, index) -> new Lookup(row.getString("lookup_id"), leaf(row, index)),
            arguments.toArray());
    if (matches.isEmpty()) {
      return Optional.empty();
    }
    if (matches.stream().map(Lookup::id).distinct().count() != 1) {
      throw new IllegalStateException("Retail family and product identifiers must not overlap");
    }
    return Optional.of(
        listing(
            matches.getFirst().id(), matches.stream().map(Lookup::leaf).toList(), window, true));
  }

  public List<InventoryAlert> alerts(int limit, int offset, Window window) {
    List<Object> arguments = arguments(window);
    arguments.add(limit + 1);
    arguments.add(offset);
    return jdbc
        .query(
            FACTS
                + " SELECT * FROM leaves WHERE facts_version IS NOT NULL"
                + " AND (stock_quantity<=low_stock_threshold OR units<=5)"
                + " ORDER BY CASE WHEN stock_quantity<=low_stock_threshold THEN 0 ELSE 1 END,stock_quantity,product_id"
                + " LIMIT ? OFFSET ?",
            this::leaf,
            arguments.toArray())
        .stream()
        .map(
            leaf -> {
              long threshold = leaf.operations().lowStockThreshold();
              long units = leaf.sales().units();
              return new InventoryAlert(
                  leaf.productId(),
                  leaf.name(),
                  leaf.stock() <= threshold ? "low_stock" : "slow_mover",
                  leaf.familyId(),
                  leaf.optionValues(),
                  leaf.stock(),
                  threshold,
                  units,
                  units == 0
                      ? null
                      : BigDecimal.valueOf(leaf.stock())
                          .multiply(BigDecimal.valueOf(30))
                          .divide(BigDecimal.valueOf(units), 2, RoundingMode.HALF_UP),
                  leaf.visible());
            })
        .toList();
  }

  private Listing listing(String id, List<Leaf> leaves, Window window, boolean detail) {
    Leaf first = leaves.getFirst();
    if (leaves.stream().anyMatch(leaf -> id.equals(leaf.productId()))
        && leaves.stream().anyMatch(leaf -> id.equals(leaf.familyId()))) {
      throw new IllegalStateException("Retail family and product identifiers must not overlap");
    }
    if (id.equals(first.productId())) {
      return sku(first, window, detail);
    }
    if (leaves.stream().anyMatch(leaf -> !first.currency().equals(leaf.currency()))) {
      throw new IllegalStateException("Retail family variants must use one currency");
    }
    String status =
        leaves.stream().anyMatch(Leaf::visible)
            ? "active"
            : leaves.stream().anyMatch(leaf -> leaf.published() && leaf.available())
                ? "out_of_stock"
                : leaves.stream().allMatch(leaf -> leaf.publicationState().equals("DRAFT"))
                    ? "draft"
                    : "paused";
    String quality =
        leaves.stream().anyMatch(leaf -> "needs_work".equals(leaf.quality()))
            ? "needs_work"
            : leaves.stream().allMatch(leaf -> "good".equals(leaf.quality())) ? "good" : null;
    String publicationState =
        leaves.stream().map(Leaf::publicationState).distinct().count() == 1
            ? first.publicationState()
            : "MIXED";
    Sales sales =
        sales(
            leaves.stream().mapToLong(leaf -> leaf.sales().orderCount()).sum(),
            leaves.stream().mapToLong(leaf -> leaf.sales().units()).sum(),
            leaves.stream().mapToLong(leaf -> leaf.sales().refundRequestedOrderCount()).sum());
    return new Listing(
        id,
        "family",
        null,
        first.familyName(),
        first.familyDescription(),
        leaves.stream().mapToLong(Leaf::priceMinor).min().orElseThrow(),
        first.currency(),
        leaves.stream().mapToLong(Leaf::stock).sum(),
        leaves.stream().anyMatch(Leaf::available),
        publicationState,
        status,
        null,
        first.familyMetadataVersion(),
        null,
        content(first.familyContent(), detail),
        first.options(),
        Map.of(),
        quality,
        null,
        sales,
        null,
        false,
        window,
        detail ? leaves.stream().map(leaf -> sku(leaf, window, true)).toList() : List.of());
  }

  private Listing sku(Leaf leaf, Window window, boolean detail) {
    Operations operations = leaf.operations();
    BigDecimal margin =
        operations == null || operations.unitCostMinor() == null || leaf.priceMinor() == 0
            ? null
            : BigDecimal.valueOf(leaf.priceMinor())
                .subtract(BigDecimal.valueOf(operations.unitCostMinor()))
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(leaf.priceMinor()), 4, RoundingMode.HALF_UP);
    String status =
        leaf.publicationState().equals("DRAFT")
            ? "draft"
            : !leaf.published() || !leaf.available()
                ? "paused"
                : leaf.stock() == 0 ? "out_of_stock" : "active";
    return new Listing(
        leaf.productId(),
        leaf.familyId() == null ? "plain" : "variant",
        leaf.familyId(),
        leaf.name(),
        leaf.description(),
        leaf.priceMinor(),
        leaf.currency(),
        leaf.stock(),
        leaf.available(),
        leaf.publicationState(),
        status,
        leaf.publicationVersion(),
        leaf.metadataVersion(),
        leaf.familyMetadataVersion(),
        content(leaf.content(), detail),
        List.of(),
        leaf.optionValues(),
        leaf.quality(),
        operations,
        leaf.sales(),
        margin,
        leaf.priceEditable(),
        window,
        List.of());
  }

  private JsonNode content(JsonNode source, boolean detail) {
    if (detail) {
      return source;
    }
    ObjectNode summary = source.deepCopy();
    summary.remove(List.of("longDescription", "specs", "reviewHighlights"));
    return summary;
  }

  private static List<Object> arguments(Window window) {
    return new ArrayList<>(
        List.of(
            Timestamp.from(window.end()),
            Timestamp.from(window.start()),
            Timestamp.from(window.end())));
  }

  private static void filter(
      StringBuilder sql, List<Object> arguments, String column, String value) {
    if (value != null) {
      sql.append(" AND ").append(column).append("=?");
      arguments.add(value);
    }
  }

  private static String score(String query, List<Object> arguments) {
    if (query == null) {
      return "0";
    }
    List<String> terms = new ArrayList<>();
    for (String token : query.split("\\s+")) {
      String pattern =
          "%"
              + token
                  .toLowerCase(Locale.ROOT)
                  .replace("!", "!!")
                  .replace("%", "!%")
                  .replace("_", "!_")
              + "%";
      for (String field :
          List.of(
              "product_id",
              "family_id",
              "name",
              "family_name",
              "description",
              "JSON_UNQUOTE(JSON_EXTRACT(content,'$.category'))",
              "CAST(JSON_EXTRACT(content,'$.attributes') AS CHAR)",
              "CAST(option_values AS CHAR)")) {
        terms.add("CASE WHEN LOWER(" + field + ") LIKE ? ESCAPE '!' THEN 1 ELSE 0 END");
        arguments.add(pattern);
      }
    }
    return String.join(" + ", terms);
  }

  private static Sales sales(long orders, long units, long refunds) {
    return new Sales(
        orders,
        units,
        refunds,
        orders == 0
            ? null
            : BigDecimal.valueOf(refunds)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(orders), 4, RoundingMode.HALF_UP));
  }

  private Leaf leaf(ResultSet row, int index) throws SQLException {
    try {
      Long factsVersion = row.getObject("facts_version", Long.class);
      Operations operations =
          factsVersion == null
              ? null
              : new Operations(
                  row.getObject("unit_cost_minor", Long.class),
                  row.getLong("low_stock_threshold"),
                  row.getString("content_quality"),
                  mapper.readValue(row.getString("missing_attributes"), STRINGS),
                  factsVersion,
                  row.getTimestamp("observed_at").toInstant(),
                  row.getString("source_ref"));
      return new Leaf(
          row.getString("product_id"),
          row.getString("root_id"),
          row.getString("name"),
          row.getString("description"),
          row.getLong("price_minor"),
          row.getString("currency"),
          row.getLong("stock_quantity"),
          row.getBoolean("available"),
          row.getString("publication_state"),
          row.getLong("publication_version"),
          row.getString("family_id"),
          row.getLong("metadata_version"),
          row.getString("family_name"),
          row.getString("family_description"),
          row.getObject("family_metadata_version", Long.class),
          mapper.readTree(row.getString("content")),
          mapper.readTree(row.getString("family_content")),
          mapper.readValue(row.getString("family_options"), OPTIONS),
          mapper.readValue(row.getString("option_values"), VALUES),
          operations,
          sales(
              row.getLong("order_count"), row.getLong("units"), row.getLong("refund_order_count")),
          row.getBoolean("price_editable"));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored merchant listing metadata is invalid", exception);
    }
  }

  private record Lookup(String id, Leaf leaf) {}

  private record Leaf(
      String productId,
      String rootId,
      String name,
      String description,
      long priceMinor,
      String currency,
      long stock,
      boolean available,
      String publicationState,
      long publicationVersion,
      String familyId,
      long metadataVersion,
      String familyName,
      String familyDescription,
      Long familyMetadataVersion,
      JsonNode content,
      JsonNode familyContent,
      List<Option> options,
      Map<String, String> optionValues,
      Operations operations,
      Sales sales,
      boolean priceEditable) {
    boolean published() {
      return publicationState.equals("PUBLISHED");
    }

    boolean visible() {
      return published() && available && stock > 0;
    }

    String quality() {
      return operations == null ? null : operations.contentQuality();
    }
  }
}
