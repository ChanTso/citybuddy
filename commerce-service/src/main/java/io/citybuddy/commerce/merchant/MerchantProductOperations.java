package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.citybuddy.commerce.catalog.ProductPublicationService;
import io.citybuddy.commerce.catalog.ProductRepository.LockedPublication;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class MerchantProductOperations {
  private static final Set<String> SHARED =
      Set.of("title", "short_description", "long_description", "category");
  private static final Set<String> SUPPLEMENTAL_ATTRIBUTES =
      Set.of("sku", "wall coverage", "material", "firmness");
  private static final Set<String> PROTECTED =
      Set.of(
          "price",
          "price_minor",
          "priceminor",
          "stock",
          "stock_quantity",
          "stockquantity",
          "available",
          "availability",
          "in_stock",
          "status",
          "publication_state",
          "publication_version",
          "currency",
          "listing_id",
          "listingid",
          "product_id",
          "productid",
          "variant_of",
          "family_id",
          "options",
          "option_values",
          "tax_category",
          "compliance_notes",
          "unit_cost",
          "unitcost",
          "unit_cost_minor",
          "margin",
          "content_quality",
          "missing_attributes");
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final ProductPublicationService publication;

  public MerchantProductOperations(
      JdbcTemplate jdbc, ObjectMapper mapper, ProductPublicationService publication) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.publication = publication;
  }

  @Transactional(
      readOnly = true,
      isolation = Isolation.READ_COMMITTED,
      noRollbackFor = MerchantException.class)
  public PreparedOperation prepare(String kind, JsonNode payload) {
    return switch (kind) {
      case "LISTING_UPDATE" -> prepareListing(payload);
      case "INVENTORY_ACTION" -> prepareInventory(payload);
      default -> throw invalid("Unsupported product operation");
    };
  }

  private PreparedOperation prepareInventory(JsonNode payload) {
    requireFields(payload, Set.of("items"));
    JsonNode requested = payload.get("items");
    if (!requested.isArray() || requested.isEmpty() || requested.size() > 25) {
      throw invalid("Inventory actions require 1 to 25 items");
    }
    Map<String, ProductState> products = new TreeMap<>();
    Map<String, FamilyState> families = new TreeMap<>();
    List<InventoryChange> changes = new ArrayList<>();
    Set<String> targets = new HashSet<>();
    ArrayNode items = mapper.createArrayNode();
    for (JsonNode request : requested) {
      if (!request.isObject()) {
        throw invalid("Inventory item must be an object");
      }
      String id = text(request.get("listingId"), 64, "listingId");
      String action = text(request.get("action"), 20, "action");
      boolean restock = action.equals("restock");
      if (!restock && !Set.of("pause", "activate").contains(action)) {
        throw invalid("Unknown inventory action");
      }
      requireFields(
          request,
          restock ? Set.of("listingId", "action", "quantity") : Set.of("listingId", "action"));
      int quantity = 0;
      if (restock) {
        JsonNode value = request.get("quantity");
        if (!value.isIntegralNumber()
            || !value.canConvertToInt()
            || value.intValue() < 1
            || value.intValue() > 500) {
          throw invalid("Restock quantity must be between 1 and 500");
        }
        quantity = value.intValue();
      }
      Target target = target(id);
      if (restock && target.family() != null) {
        throw invalid("Restock requires a leaf SKU");
      }
      if (target.family() != null) {
        families.put(target.family().familyId(), target.family());
      }
      for (ProductState product : target.products()) {
        ensureEligible(product);
        if (!restock && !"PUBLISHED".equals(product.publicationState())) {
          throw new MerchantException(
              409, "not_published", "Pause and activate require an already published SKU");
        }
        String field = restock ? "stock" : "available";
        if (!targets.add(product.productId() + ":" + field)) {
          throw invalid("An inventory field cannot be changed twice for the same SKU");
        }
        if (targets.size() > 25) {
          throw invalid("Expanded inventory actions exceed 25 items");
        }
        if (restock && product.stock() > Long.MAX_VALUE - quantity) {
          throw invalid("Resulting inventory exceeds the supported range");
        }
        products.put(product.productId(), product);
        changes.add(new InventoryChange(product.productId(), action, quantity));
        items.add(
            item(
                product.productId(),
                field,
                restock
                    ? mapper.valueToTree(product.stock())
                    : mapper.valueToTree(product.available()),
                restock
                    ? mapper.valueToTree(product.stock() + quantity)
                    : mapper.valueToTree(action.equals("activate"))));
      }
    }
    return prepared(
        "INVENTORY_ACTION", null, mapper.createObjectNode(), products, families, changes, items);
  }

  private PreparedOperation prepareListing(JsonNode payload) {
    requireFields(payload, Set.of("listingId", "fields"));
    Target target = target(text(payload.get("listingId"), 64, "listingId"));
    JsonNode fields = payload.get("fields");
    if (!fields.isObject() || fields.isEmpty() || fields.size() > 25) {
      throw invalid("Listing updates require 1 to 25 fields");
    }
    JsonNode effective =
        target.family() == null
            ? inheritedContent(target.products().getFirst())
            : target.family().content();
    ArrayNode items = mapper.createArrayNode();
    ObjectNode normalized = mapper.createObjectNode();
    List<String> names = new ArrayList<>();
    fields.fieldNames().forEachRemaining(names::add);
    names.sort(String::compareTo);
    for (String name : names) {
      if (target.family() == null
          && target.products().getFirst().familyId() != null
          && SHARED.contains(name)) {
        throw new MerchantException(
            409,
            "shared_family_content",
            "Edit shared content on family " + target.products().getFirst().familyId());
      }
      if (PROTECTED.contains(name.toLowerCase(Locale.ROOT))) {
        throw invalid("Protected listing field: " + name);
      }
      if (!SHARED.contains(name)
          && !SUPPLEMENTAL_ATTRIBUTES.contains(name)
          && !effective.path("attributes").has(name)) {
        throw invalid("Unsupported editable listing field: " + name);
      }
      String value = text(fields.get(name), name.equals("title") ? 200 : 2000, name);
      normalized.put(name, value);
      items.add(
          item(
              target.id(),
              name,
              beforeContent(target, effective, name),
              mapper.valueToTree(value)));
    }
    Map<String, ProductState> products = new TreeMap<>();
    for (ProductState product : target.products()) {
      ensureEligible(product);
      products.put(product.productId(), product);
    }
    Map<String, FamilyState> families = new TreeMap<>();
    if (target.family() != null) {
      families.put(target.id(), target.family());
    }
    return prepared(
        "LISTING_UPDATE", target.id(), normalized, products, families, List.of(), items);
  }

  private PreparedOperation prepared(
      String kind,
      String listingId,
      ObjectNode fields,
      Map<String, ProductState> products,
      Map<String, FamilyState> families,
      List<InventoryChange> inventory,
      ArrayNode items) {
    if (products.isEmpty() || products.size() > 25) {
      throw invalid("A product operation must touch between 1 and 25 leaf SKUs");
    }
    String currency = products.values().iterator().next().currency();
    if (products.values().stream().anyMatch(product -> !currency.equals(product.currency()))) {
      throw new MerchantException(
          409, "currency_mismatch", "One operation must use a single currency");
    }
    for (ProductState product : products.values()) {
      if (product.familyId() != null && !families.containsKey(product.familyId())) {
        families.put(product.familyId(), family(product.familyId(), false));
      }
    }
    var snapshot =
        new Snapshot(
            kind,
            listingId,
            fields,
            List.copyOf(products.values()),
            List.copyOf(families.values()),
            List.copyOf(inventory));
    return new PreparedOperation(currency, items, mapper.valueToTree(snapshot));
  }

  @Transactional(
      isolation = Isolation.READ_COMMITTED,
      noRollbackFor = MerchantProductOperationException.class)
  public JsonNode apply(String kind, JsonNode stored) {
    Snapshot snapshot;
    try {
      snapshot = mapper.treeToValue(stored, Snapshot.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Invalid persisted product operation", exception);
    }
    if (!kind.equals(snapshot.kind())
        || !Set.of("LISTING_UPDATE", "INVENTORY_ACTION").contains(kind)) {
      throw new IllegalStateException("Product operation snapshot kind conflicts with its draft");
    }
    Map<String, ProductState> current = new LinkedHashMap<>();
    for (ProductState expected :
        snapshot.products().stream()
            .sorted(Comparator.comparing(ProductState::productId))
            .toList()) {
      ProductState product = product(expected.productId(), true);
      if (product == null) {
        throw rejected("NOT_FOUND", expected.productId());
      }
      current.put(product.productId(), product);
    }
    Map<String, FamilyState> families = new TreeMap<>();
    for (FamilyState expected : snapshot.families()) {
      FamilyState family = family(expected.familyId(), true);
      if (family == null
          || family.version() != expected.version()
          || !family.members().equals(expected.members())) {
        throw rejected("FAMILY_VERSION_CONFLICT", expected.familyId());
      }
      families.put(family.familyId(), family);
    }
    // Metadata locks follow product locks, matching every product-operation writer.
    for (ProductState expected : snapshot.products()) {
      ProductState product = current.get(expected.productId());
      Metadata metadata = metadata(product.productId(), true);
      FactsState facts = facts(product.productId(), true);
      if (product.version() != expected.version()
          || metadata.version() != expected.metadataVersion()
          || !java.util.Objects.equals(metadata.familyId(), expected.familyId())
          || factsVersion(facts) != factsVersion(expected.facts())) {
        throw rejected("VERSION_CONFLICT", product.productId());
      }
      if (!product.currency().equals(expected.currency())) {
        throw rejected("CURRENCY_MISMATCH", product.productId());
      }
      if (seckill(product.productId())) {
        throw rejected("SECKILL_PRODUCT", product.productId());
      }
      if (snapshot.inventory().stream()
              .anyMatch(
                  change ->
                      change.productId().equals(product.productId())
                          && !change.action().equals("restock"))
          && !"PUBLISHED".equals(product.publicationState())) {
        throw rejected("NOT_PUBLISHED", product.productId());
      }
      current.put(product.productId(), product.withMetadata(metadata).withFacts(facts));
    }
    List<ProductWrite> writes = new ArrayList<>();
    List<FamilyWrite> familyWrites = new ArrayList<>();
    List<FactsWrite> factsWrites = new ArrayList<>();
    if (kind.equals("INVENTORY_ACTION")) {
      inventoryWrites(snapshot, current, writes);
    } else {
      listingWrites(snapshot, current, families, writes, familyWrites);
      for (ProductState product : current.values()) {
        FactsState facts = product.facts();
        if (facts == null) {
          continue;
        }
        boolean familyEdit = families.containsKey(snapshot.listingId());
        List<String> missing =
            facts.missing().stream()
                .filter(
                    key ->
                        !snapshot.fields().has(key)
                            || (familyEdit
                                && !SHARED.contains(key)
                                && product.content().path("attributes").has(key)))
                .toList();
        if (!missing.equals(facts.missing())) {
          factsWrites.add(
              new FactsWrite(
                  product.productId(),
                  missing,
                  "needs_work".equals(facts.quality()) && missing.isEmpty()
                      ? "good"
                      : facts.quality(),
                  add(facts.version(), 1, product.productId())));
        }
      }
    }
    // Every business rejection, including arithmetic bounds, occurs before the first write.
    for (ProductWrite write : writes) {
      ProductState before = write.before();
      int changed =
          jdbc.update(
              "UPDATE product SET name=?,description=?,stock_quantity=?,available=?,publication_version=?"
                  + " WHERE product_id=? AND publication_version=?",
              write.name(),
              write.description(),
              write.stock(),
              write.available(),
              write.version(),
              before.productId(),
              before.version());
      if (changed != 1) {
        throw new IllegalStateException("Product changed while its operation held the row lock");
      }
      if (write.content() != null) {
        writeMetadata(before, write.content(), write.metadataVersion());
      }
    }
    for (FamilyWrite write : familyWrites) {
      jdbc.update(
          "UPDATE retail_product_family SET name=?,description=?,content=CAST(? AS JSON),metadata_version=? WHERE family_id=?",
          write.name(),
          write.description(),
          json(write.content()),
          write.version(),
          write.before().familyId());
    }
    for (FactsWrite write : factsWrites) {
      jdbc.update(
          "UPDATE retail_product_operations SET missing_attributes=CAST(? AS JSON),content_quality=?,"
              + "facts_version=?,observed_at=CURRENT_TIMESTAMP(6) WHERE product_id=?",
          json(mapper.valueToTree(write.missing())),
          write.quality(),
          write.version(),
          write.productId());
    }
    var events =
        publication.publishLockedChanges(
            writes.stream()
                .map(
                    write ->
                        new LockedPublication(
                            write.before().productId(),
                            write.version(),
                            "PUBLISHED".equals(write.before().publicationState())))
                .toList());
    ObjectNode result = mapper.createObjectNode();
    ArrayNode changes = result.putArray("changes");
    for (ProductWrite write : writes) {
      changes
          .addObject()
          .put("productId", write.before().productId())
          .put("oldVersion", write.before().version())
          .put("newVersion", write.version())
          .put("beforeStock", write.before().stock())
          .put("afterStock", write.stock())
          .put("beforeAvailable", write.before().available())
          .put("afterAvailable", write.available());
    }
    result.set("events", mapper.valueToTree(events.stream().map(event -> event.event()).toList()));
    if (kind.equals("LISTING_UPDATE")) {
      result.set("fields", snapshot.fields());
      result.set(
          "families",
          mapper.valueToTree(
              familyWrites.stream()
                  .map(
                      write ->
                          Map.of(
                              "familyId",
                              write.before().familyId(),
                              "oldVersion",
                              write.before().version(),
                              "newVersion",
                              write.version()))
                  .toList()));
    }
    return result;
  }

  private void inventoryWrites(
      Snapshot snapshot, Map<String, ProductState> products, List<ProductWrite> writes) {
    for (ProductState product : products.values()) {
      long stock = product.stock();
      boolean available = product.available();
      for (InventoryChange change : snapshot.inventory()) {
        if (!change.productId().equals(product.productId())) {
          continue;
        }
        if (change.action().equals("restock")) {
          stock = add(stock, change.quantity(), product.productId());
        } else {
          available = change.action().equals("activate");
        }
      }
      writes.add(
          new ProductWrite(
              product,
              product.name(),
              product.description(),
              stock,
              available,
              add(product.version(), 1, product.productId()),
              null,
              product.metadataVersion()));
    }
  }

  private void listingWrites(
      Snapshot snapshot,
      Map<String, ProductState> products,
      Map<String, FamilyState> families,
      List<ProductWrite> writes,
      List<FamilyWrite> familyWrites) {
    ObjectNode fields = snapshot.fields();
    FamilyState family = families.get(snapshot.listingId());
    if (family != null) {
      ObjectNode content = updateContent(family.content(), fields);
      familyWrites.add(
          new FamilyWrite(
              family,
              field(fields, "title", family.name()),
              field(fields, "short_description", family.description()),
              content,
              add(family.version(), 1, family.familyId())));
    }
    for (ProductState product : products.values()) {
      ObjectNode content = product.content().deepCopy();
      if (family == null) {
        content = updateContent(content, fields);
      } else {
        if (fields.has("long_description")) {
          content.remove("longDescription");
        }
        if (fields.has("category")) {
          content.remove("category");
        }
      }
      boolean changedContent = !content.equals(product.content());
      writes.add(
          new ProductWrite(
              product,
              field(fields, "title", product.name()),
              field(fields, "short_description", product.description()),
              product.stock(),
              product.available(),
              add(product.version(), 1, product.productId()),
              changedContent ? content : null,
              changedContent
                  ? add(product.metadataVersion(), 1, product.productId())
                  : product.metadataVersion()));
    }
  }

  private ObjectNode updateContent(ObjectNode original, ObjectNode fields) {
    ObjectNode result = original.deepCopy();
    fields
        .fields()
        .forEachRemaining(
            entry -> {
              switch (entry.getKey()) {
                case "title", "short_description" -> {}
                case "long_description" -> result.set("longDescription", entry.getValue());
                case "category" -> result.set("category", entry.getValue());
                default -> result.withObject("attributes").set(entry.getKey(), entry.getValue());
              }
            });
    return result;
  }

  private void writeMetadata(ProductState product, ObjectNode content, long version) {
    if (product.metadataVersion() == 0) {
      jdbc.update(
          "INSERT INTO retail_product_metadata(product_id,family_id,content,option_values,metadata_version)"
              + " VALUES (?,NULL,CAST(? AS JSON),'{}',?)",
          product.productId(),
          json(content),
          version);
    } else {
      jdbc.update(
          "UPDATE retail_product_metadata SET content=CAST(? AS JSON),metadata_version=? WHERE product_id=?",
          json(content),
          version,
          product.productId());
    }
  }

  private Target target(String requestedId) {
    ProductState product = product(requestedId, false);
    FamilyState family = family(requestedId, false);
    if (product != null && family != null) {
      throw new IllegalStateException("Product and family identifiers overlap");
    }
    if (product != null) {
      return new Target(product.productId(), null, List.of(product));
    }
    if (family == null) {
      throw new MerchantException(404, "not_found", "Listing does not exist");
    }
    if (family.members().isEmpty() || family.members().size() > 25) {
      throw invalid("Family operation requires 1 to 25 leaf SKUs");
    }
    return new Target(
        family.familyId(),
        family,
        family.members().stream().map(id -> product(id, false)).toList());
  }

  private ProductState product(String id, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT product_id,name,description,price_minor,currency,stock_quantity,available,publication_state,publication_version"
                + " FROM product WHERE product_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (row, index) ->
                new ProductState(
                    row.getString("product_id"),
                    row.getString("name"),
                    row.getString("description"),
                    row.getLong("price_minor"),
                    row.getString("currency"),
                    row.getLong("stock_quantity"),
                    row.getBoolean("available"),
                    row.getString("publication_state"),
                    row.getLong("publication_version"),
                    null,
                    0,
                    mapper.createObjectNode(),
                    null),
            id);
    if (rows.isEmpty()) {
      return null;
    }
    ProductState product = rows.getFirst();
    return lock
        ? product
        : product
            .withMetadata(metadata(product.productId(), false))
            .withFacts(facts(product.productId(), false));
  }

  private FactsState facts(String id, boolean lock) {
    return jdbc
        .query(
            "SELECT facts_version,content_quality,missing_attributes FROM retail_product_operations WHERE product_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (row, index) -> {
              JsonNode missing;
              try {
                missing = mapper.readTree(row.getString("missing_attributes"));
              } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Invalid stored missing attributes", exception);
              }
              if (!missing.isArray()) {
                throw new IllegalStateException("Stored missing attributes must be an array");
              }
              List<String> values = new ArrayList<>();
              for (JsonNode value : missing) {
                if (!value.isTextual() || value.textValue().isBlank()) {
                  throw new IllegalStateException("Stored missing attribute must be text");
                }
                values.add(value.textValue());
              }
              return new FactsState(
                  row.getLong("facts_version"),
                  row.getString("content_quality"),
                  List.copyOf(values));
            },
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private static long factsVersion(FactsState facts) {
    return facts == null ? 0 : facts.version();
  }

  private Metadata metadata(String id, boolean lock) {
    return jdbc
        .query(
            "SELECT family_id,metadata_version,content FROM retail_product_metadata WHERE product_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (row, index) ->
                new Metadata(
                    row.getString("family_id"),
                    row.getLong("metadata_version"),
                    object(row.getString("content"))),
            id)
        .stream()
        .findFirst()
        .orElseGet(() -> new Metadata(null, 0, mapper.createObjectNode()));
  }

  private FamilyState family(String id, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT family_id,name,description,content,metadata_version FROM retail_product_family WHERE family_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (row, index) ->
                new FamilyState(
                    row.getString("family_id"),
                    row.getString("name"),
                    row.getString("description"),
                    object(row.getString("content")),
                    row.getLong("metadata_version"),
                    List.of()),
            id);
    if (rows.isEmpty()) {
      return null;
    }
    var family = rows.getFirst();
    var members =
        jdbc.queryForList(
            "SELECT product_id FROM retail_product_metadata WHERE family_id=? ORDER BY product_id",
            String.class,
            family.familyId());
    return new FamilyState(
        family.familyId(),
        family.name(),
        family.description(),
        family.content(),
        family.version(),
        members);
  }

  private JsonNode inheritedContent(ProductState product) {
    if (product.familyId() == null) {
      return product.content();
    }
    var family = family(product.familyId(), false);
    ObjectNode result = family.content().deepCopy();
    product
        .content()
        .fields()
        .forEachRemaining(
            entry -> {
              if (entry.getValue().isObject() && result.path(entry.getKey()).isObject()) {
                ((ObjectNode) result.get(entry.getKey())).setAll((ObjectNode) entry.getValue());
              } else {
                result.set(entry.getKey(), entry.getValue());
              }
            });
    return result;
  }

  private JsonNode beforeContent(Target target, JsonNode content, String name) {
    return switch (name) {
      case "title" ->
          mapper.valueToTree(
              target.family() == null
                  ? target.products().getFirst().name()
                  : target.family().name());
      case "short_description" ->
          mapper.valueToTree(
              target.family() == null
                  ? target.products().getFirst().description()
                  : target.family().description());
      case "long_description" -> content.path("longDescription");
      case "category" -> content.path("category");
      default -> content.path("attributes").path(name);
    };
  }

  private void ensureEligible(ProductState product) {
    if (seckill(product.productId())) {
      throw new MerchantException(
          409, "seckill_product", "Products referenced by seckill cannot be changed");
    }
  }

  private boolean seckill(String id) {
    return !jdbc.queryForList(
            "SELECT activity_id FROM seckill_activity WHERE product_id=? LIMIT 1", String.class, id)
        .isEmpty();
  }

  private ObjectNode object(String json) {
    try {
      JsonNode value = mapper.readTree(json);
      if (value instanceof ObjectNode object) {
        return object;
      }
      throw new IllegalStateException("Stored product content must be an object");
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Invalid stored product content", exception);
    }
  }

  private String json(JsonNode value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Product operation serialization failed", exception);
    }
  }

  private ObjectNode item(String target, String field, JsonNode before, JsonNode after) {
    ObjectNode item = mapper.createObjectNode().put("target", target).put("field", field);
    item.set("before", before);
    item.set("after", after);
    return item;
  }

  private static String field(ObjectNode fields, String key, String fallback) {
    return fields.has(key) ? fields.get(key).textValue() : fallback;
  }

  private static long add(long before, long delta, String id) {
    try {
      return Math.addExact(before, delta);
    } catch (ArithmeticException exception) {
      throw rejected("VALUE_OUT_OF_RANGE", id);
    }
  }

  private static String text(JsonNode node, int maximum, String name) {
    if (node == null
        || !node.isTextual()
        || node.textValue().isBlank()
        || node.textValue().length() > maximum) {
      throw invalid("Invalid " + name);
    }
    return node.textValue();
  }

  private static void requireFields(JsonNode node, Set<String> fields) {
    if (node == null || !node.isObject() || node.size() != fields.size()) {
      throw invalid("Invalid operation fields");
    }
    for (String field : fields) {
      if (!node.has(field)) {
        throw invalid("Missing operation field: " + field);
      }
    }
  }

  private static MerchantException invalid(String message) {
    return new MerchantException(400, "validation", message);
  }

  private static MerchantProductOperationException rejected(String reason, String id) {
    return new MerchantProductOperationException(reason, id);
  }

  public record PreparedOperation(String currency, JsonNode items, JsonNode snapshot) {}

  public record Snapshot(
      String kind,
      String listingId,
      ObjectNode fields,
      List<ProductState> products,
      List<FamilyState> families,
      List<InventoryChange> inventory) {}

  public record InventoryChange(String productId, String action, int quantity) {}

  public record FamilyState(
      String familyId,
      String name,
      String description,
      ObjectNode content,
      long version,
      List<String> members) {}

  public record ProductState(
      String productId,
      String name,
      String description,
      long price,
      String currency,
      long stock,
      boolean available,
      String publicationState,
      long version,
      String familyId,
      long metadataVersion,
      ObjectNode content,
      FactsState facts) {
    ProductState withMetadata(Metadata metadata) {
      return new ProductState(
          productId,
          name,
          description,
          price,
          currency,
          stock,
          available,
          publicationState,
          version,
          metadata.familyId(),
          metadata.version(),
          metadata.content(),
          facts);
    }

    ProductState withFacts(FactsState value) {
      return new ProductState(
          productId,
          name,
          description,
          price,
          currency,
          stock,
          available,
          publicationState,
          version,
          familyId,
          metadataVersion,
          content,
          value);
    }
  }

  public record FactsState(long version, String quality, List<String> missing) {}

  private record Metadata(String familyId, long version, ObjectNode content) {}

  private record Target(String id, FamilyState family, List<ProductState> products) {}

  private record ProductWrite(
      ProductState before,
      String name,
      String description,
      long stock,
      boolean available,
      long version,
      ObjectNode content,
      long metadataVersion) {}

  private record FamilyWrite(
      FamilyState before, String name, String description, ObjectNode content, long version) {}

  private record FactsWrite(String productId, List<String> missing, String quality, long version) {}
}
