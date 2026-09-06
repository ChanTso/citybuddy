package io.citybuddy.commerce.retail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.citybuddy.commerce.retail.RetailCatalogModels.Option;
import io.citybuddy.commerce.retail.RetailCatalogModels.Search;
import io.citybuddy.commerce.retail.RetailCatalogModels.View;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

public final class RetailCatalogRepository {
  private static final TypeReference<List<Option>> OPTIONS = new TypeReference<>() {};
  private static final TypeReference<Map<String, String>> OPTION_VALUES = new TypeReference<>() {};
  private static final String LEAVES =
      """
      SELECT p.product_id, p.name, p.description, p.price_minor, p.currency,
             p.stock_quantity, p.available, p.publication_version,
             m.family_id, COALESCE(m.metadata_version, 0) AS metadata_version,
             COALESCE(m.display_order, 0) AS display_order,
             COALESCE(m.option_values, JSON_OBJECT()) AS option_values,
             f.name AS family_name, f.description AS family_description,
             f.metadata_version AS family_metadata_version,
             COALESCE(f.content, JSON_OBJECT()) AS family_content,
             COALESCE(f.content, m.content, JSON_OBJECT()) AS summary_content,
             COALESCE(f.options, JSON_ARRAY()) AS family_options,
             JSON_MERGE_PATCH(COALESCE(f.content, JSON_OBJECT()),
                              COALESCE(m.content, JSON_OBJECT())) AS content,
             COALESCE(m.family_id, p.product_id) AS root_id,
             COALESCE(f.display_order, m.display_order, 0) AS root_order
      FROM product p
      LEFT JOIN retail_product_metadata m ON m.product_id = p.product_id
      LEFT JOIN retail_product_family f ON f.family_id = m.family_id
      WHERE p.publication_state = 'PUBLISHED'
      """;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public RetailCatalogRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public List<View> search(Search request) {
    List<Object> arguments = new ArrayList<>();
    String score = score(request.query(), arguments);
    StringBuilder sql =
        new StringBuilder("WITH leaves AS (")
            .append(LEAVES)
            .append("), scored AS (SELECT leaves.*, ")
            .append(score)
            .append(" AS score FROM leaves), matching AS (SELECT * FROM scored WHERE 1=1");
    if (request.query() != null) {
      sql.append(" AND score > 0");
    }
    if (request.category() != null) {
      sql.append(" AND JSON_UNQUOTE(JSON_EXTRACT(summary_content, '$.category')) = ?");
      arguments.add(request.category());
    }
    if (request.currency() != null) {
      sql.append(" AND currency = ?");
      arguments.add(request.currency());
    }
    if (request.minPriceMinor() != null) {
      sql.append(" AND price_minor >= ?");
      arguments.add(request.minPriceMinor());
    }
    if (request.maxPriceMinor() != null) {
      sql.append(" AND price_minor <= ?");
      arguments.add(request.maxPriceMinor());
    }
    if (request.minRating() != null) {
      sql.append(
          " AND CAST(JSON_UNQUOTE(JSON_EXTRACT(summary_content, '$.rating')) AS DECIMAL(4,2)) >= ?");
      arguments.add(request.minRating());
    }
    request
        .attributes()
        .forEach(
            (key, value) -> {
              // A SKU's selected option overrides broad descriptive family attributes.
              sql.append(
                  " AND CASE WHEN JSON_CONTAINS_PATH(option_values, 'one', ?)"
                      + " THEN JSON_UNQUOTE(JSON_EXTRACT(option_values, ?))"
                      + " ELSE JSON_UNQUOTE(JSON_EXTRACT(content, ?)) END = ?");
              String optionPath = jsonPath(key);
              arguments.add(optionPath);
              arguments.add(optionPath);
              arguments.add("$.attributes" + optionPath.substring(1));
              arguments.add(value);
            });
    sql.append(
        ") SELECT roots.root_id FROM leaves roots"
            + " JOIN (SELECT root_id, MAX(score) AS score FROM matching GROUP BY root_id) eligible"
            + " ON eligible.root_id = roots.root_id GROUP BY roots.root_id ORDER BY "
            + switch (request.sort()) {
              case "price_asc" ->
                  "COALESCE(MIN(CASE WHEN available AND stock_quantity > 0 THEN price_minor END),"
                      + " MIN(price_minor)) ASC, ";
              case "price_desc" ->
                  "COALESCE(MIN(CASE WHEN available AND stock_quantity > 0 THEN price_minor END),"
                      + " MIN(price_minor)) DESC, ";
              case "rating" ->
                  "MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(summary_content, '$.rating')) AS DECIMAL(4,2))) DESC, ";
              default -> "MAX(score) DESC, ";
            });
    sql.append("MIN(root_order), roots.root_id LIMIT ? OFFSET ?");
    arguments.add(request.limit());
    arguments.add(request.offset());
    List<String> ids =
        jdbc.query(sql.toString(), (row, index) -> row.getString("root_id"), arguments.toArray());
    if (ids.isEmpty()) {
      return List.of();
    }
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    List<Object> detailArguments = new ArrayList<>(ids);
    detailArguments.addAll(ids);
    List<Leaf> leaves =
        jdbc.query(
            LEAVES
                + " AND (p.product_id IN ("
                + placeholders
                + ") OR m.family_id IN ("
                + placeholders
                + ")) ORDER BY root_order, display_order, p.product_id",
            this::leaf,
            detailArguments.toArray());
    Map<String, List<Leaf>> roots = group(leaves);
    return ids.stream().map(id -> view(id, roots.get(id), false)).toList();
  }

  public Optional<View> find(String id) {
    List<Lookup> matches =
        jdbc.query(
            "WITH leaves AS ("
                + LEAVES
                + ") SELECT leaves.*, CASE WHEN product_id = ? THEN product_id"
                + " ELSE family_id END AS lookup_id FROM leaves"
                + " WHERE product_id = ? OR family_id = ? ORDER BY display_order, product_id",
            (row, index) -> new Lookup(row.getString("lookup_id"), leaf(row, index)),
            id,
            id,
            id);
    if (matches.isEmpty()) {
      return Optional.empty();
    }
    if (matches.stream().map(Lookup::id).distinct().count() != 1) {
      throw new IllegalStateException("Retail family and product identifiers must not overlap");
    }
    // Use the stored identifier after the database's collation-aware lookup.
    return Optional.of(
        view(matches.getFirst().id(), matches.stream().map(Lookup::leaf).toList(), true));
  }

  private String score(String query, List<Object> arguments) {
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
      String[] fields = {
        "name",
        "family_name",
        "JSON_UNQUOTE(JSON_EXTRACT(content, '$.brand'))",
        "JSON_UNQUOTE(JSON_EXTRACT(content, '$.category'))",
        "description",
        "CAST(JSON_EXTRACT(content, '$.attributes') AS CHAR)",
        "CAST(option_values AS CHAR)",
        "JSON_UNQUOTE(JSON_EXTRACT(content, '$.longDescription'))",
        "CAST(JSON_EXTRACT(content, '$.specs') AS CHAR)"
      };
      int[] weights = {4, 2, 2, 2, 1, 1, 1, 1, 1};
      for (int index = 0; index < fields.length; index++) {
        terms.add(
            "CASE WHEN LOWER("
                + fields[index]
                + ") LIKE ? ESCAPE '!' THEN "
                + weights[index]
                + " ELSE 0 END");
        arguments.add(pattern);
      }
    }
    return String.join(" + ", terms);
  }

  private Map<String, List<Leaf>> group(List<Leaf> leaves) {
    Map<String, List<Leaf>> result = new LinkedHashMap<>();
    for (Leaf leaf : leaves) {
      result
          .computeIfAbsent(
              leaf.familyId() == null ? leaf.productId() : leaf.familyId(),
              key -> new ArrayList<>())
          .add(leaf);
    }
    return result;
  }

  private View view(String id, List<Leaf> leaves, boolean detail) {
    Leaf first = leaves.getFirst();
    if (leaves.stream().anyMatch(leaf -> id.equals(leaf.productId()))
        && leaves.stream().anyMatch(leaf -> id.equals(leaf.familyId()))) {
      throw new IllegalStateException("Retail family and product identifiers must not overlap");
    }
    if (id.equals(first.productId())) {
      return sku(first, detail);
    }
    String currency = first.currency();
    if (leaves.stream().anyMatch(leaf -> !currency.equals(leaf.currency()))) {
      throw new IllegalStateException("Retail family variants must use one currency");
    }
    Leaf priced =
        leaves.stream()
            .min(
                Comparator.comparing((Leaf leaf) -> !leaf.inStock())
                    .thenComparingLong(Leaf::priceMinor))
            .orElseThrow();
    return new View(
        id,
        "family",
        null,
        null,
        first.familyName(),
        first.familyDescription(),
        priced.priceMinor(),
        currency,
        leaves.stream().mapToLong(Leaf::stockQuantity).sum(),
        leaves.stream().anyMatch(Leaf::available),
        leaves.stream().anyMatch(Leaf::inStock),
        null,
        first.familyMetadataVersion(),
        null,
        content(first.familyContent(), detail),
        first.options(),
        Map.of(),
        detail ? leaves.stream().map(leaf -> sku(leaf, true)).toList() : List.of());
  }

  private View sku(Leaf leaf, boolean detail) {
    return new View(
        leaf.productId(),
        leaf.familyId() == null ? "plain" : "variant",
        leaf.productId(),
        leaf.familyId(),
        leaf.name(),
        leaf.description(),
        leaf.priceMinor(),
        leaf.currency(),
        leaf.stockQuantity(),
        leaf.available(),
        leaf.inStock(),
        leaf.publicationVersion(),
        leaf.metadataVersion(),
        leaf.familyMetadataVersion(),
        content(leaf.content(), detail),
        List.of(),
        leaf.optionValues(),
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

  private String jsonPath(String key) {
    try {
      return "$." + mapper.writeValueAsString(key);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Attribute key cannot be serialized", exception);
    }
  }

  private Leaf leaf(ResultSet row, int index) throws SQLException {
    try {
      return new Leaf(
          row.getString("product_id"),
          row.getString("name"),
          row.getString("description"),
          row.getLong("price_minor"),
          row.getString("currency"),
          row.getLong("stock_quantity"),
          row.getBoolean("available"),
          row.getLong("publication_version"),
          row.getString("family_id"),
          row.getLong("metadata_version"),
          row.getString("family_name"),
          row.getString("family_description"),
          row.getObject("family_metadata_version", Long.class),
          mapper.readTree(row.getString("content")),
          mapper.readTree(row.getString("family_content")),
          mapper.readValue(row.getString("family_options"), OPTIONS),
          mapper.readValue(row.getString("option_values"), OPTION_VALUES));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored retail metadata is invalid", exception);
    }
  }

  private record Lookup(String id, Leaf leaf) {}

  private record Leaf(
      String productId,
      String name,
      String description,
      long priceMinor,
      String currency,
      long stockQuantity,
      boolean available,
      long publicationVersion,
      String familyId,
      long metadataVersion,
      String familyName,
      String familyDescription,
      Long familyMetadataVersion,
      JsonNode content,
      JsonNode familyContent,
      List<Option> options,
      Map<String, String> optionValues) {
    boolean inStock() {
      return available && stockQuantity > 0;
    }
  }
}
