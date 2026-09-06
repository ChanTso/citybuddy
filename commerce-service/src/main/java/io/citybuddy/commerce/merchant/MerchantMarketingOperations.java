package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.citybuddy.commerce.catalog.ProductPriceChangeException;
import io.citybuddy.commerce.catalog.ProductPublicationService;
import io.citybuddy.commerce.catalog.ProductRepository.PriceChange;
import io.citybuddy.commerce.merchant.MerchantMarketingModels.Campaign;
import io.citybuddy.commerce.merchant.MerchantMarketingModels.CampaignSnapshot;
import io.citybuddy.commerce.merchant.MerchantMarketingModels.FamilyTarget;
import io.citybuddy.commerce.merchant.MerchantMarketingModels.PreparedOperation;
import io.citybuddy.commerce.merchant.MerchantMarketingModels.ProductTarget;
import io.citybuddy.commerce.merchant.MerchantMarketingModels.PromotionSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class MerchantMarketingOperations {
  private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Shanghai");
  private static final Set<String> PROMOTION_FIELDS =
      Set.of("name", "listingIds", "discountPct", "starts", "ends");
  private static final Set<String> CAMPAIGN_FIELDS =
      Set.of(
          "campaignId",
          "name",
          "objective",
          "audience",
          "budgetMinor",
          "copyText",
          "starts",
          "ends");
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final MerchantMarketingRepository repository;
  private final ProductPublicationService publication;
  private final Clock clock;

  public MerchantMarketingOperations(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      MerchantMarketingRepository repository,
      ProductPublicationService publication,
      Clock clock) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.repository = repository;
    this.publication = publication;
    this.clock = clock;
  }

  @Transactional(
      readOnly = true,
      isolation = Isolation.READ_COMMITTED,
      noRollbackFor = MerchantException.class)
  public PreparedOperation prepare(String kind, JsonNode payload) {
    return switch (kind) {
      case "PROMOTION" -> preparePromotion(payload);
      case "CAMPAIGN" -> prepareCampaign(payload);
      default -> throw invalid("Unsupported marketing change kind");
    };
  }

  private PreparedOperation preparePromotion(JsonNode payload) {
    shape(payload, PROMOTION_FIELDS, PROMOTION_FIELDS);
    String name = text(payload.get("name"), 80, "name");
    int basisPoints = discount(payload.get("discountPct"));
    Instant start = date(payload.get("starts"), false);
    Instant end = date(payload.get("ends"), true);
    window(start, end);
    JsonNode listings = payload.get("listingIds");
    if (!listings.isArray() || listings.isEmpty() || listings.size() > 25) {
      throw invalid("Promotion requires one to twenty-five listings");
    }
    Map<String, ProductTarget> products = new TreeMap<>();
    Map<String, FamilyTarget> families = new TreeMap<>();
    for (JsonNode listing : listings) {
      String id = text(listing, 64, "listingId");
      ProductTarget product = product(id, false);
      FamilyTarget family = family(id, false);
      if (product != null && family != null) {
        throw new IllegalStateException("Product and family identifiers overlap");
      }
      if (product == null && family == null) {
        throw new MerchantException(404, "not_found", "Promotion listing does not exist");
      }
      List<ProductTarget> targets;
      if (family != null) {
        if (families.put(family.familyId(), family) != null
            || family.members().isEmpty()
            || family.members().size() > 25) {
          throw invalid("Promotion families must be unique and contain one to twenty-five SKUs");
        }
        targets = family.members().stream().map(member -> product(member, false)).toList();
      } else {
        targets = List.of(product);
      }
      for (ProductTarget target : targets) {
        if (target == null) {
          throw invalid("Promotion family changed during preparation; read it again");
        }
        long targetPrice = discounted(target.priceMinor(), basisPoints);
        ProductTarget priced =
            new ProductTarget(
                target.productId(),
                target.name(),
                target.priceMinor(),
                targetPrice,
                target.currency(),
                target.version());
        if (products.put(target.productId(), priced) != null || products.size() > 25) {
          throw invalid("Expanded promotion SKUs must be unique and at most twenty-five");
        }
      }
    }
    String currency = products.values().iterator().next().currency();
    if (products.values().stream().anyMatch(target -> !currency.equals(target.currency()))) {
      throw invalid("Promotion targets must use one currency");
    }
    PromotionSnapshot snapshot =
        new PromotionSnapshot(
            name,
            basisPoints,
            start,
            end,
            currency,
            List.copyOf(products.values()),
            List.copyOf(families.values()));
    ArrayNode items = mapper.createArrayNode();
    for (ProductTarget target : snapshot.products()) {
      items.add(
          item(
              target.productId(),
              "promotion_price",
              target.priceMinor(),
              target.targetPriceMinor()));
    }
    return new PreparedOperation(currency, items, mapper.valueToTree(snapshot));
  }

  private PreparedOperation prepareCampaign(JsonNode payload) {
    shape(payload, CAMPAIGN_FIELDS, Set.of("name"));
    String id = optionalText(payload.get("campaignId"), 64, "campaignId");
    Campaign prior =
        id == null
            ? null
            : repository
                .campaign(id)
                .orElseThrow(
                    () -> new MerchantException(404, "not_found", "Campaign does not exist"));
    String name = text(payload.get("name"), 80, "name");
    String objective = field(payload, "objective", 200, prior == null ? null : prior.objective());
    String audience = field(payload, "audience", 300, prior == null ? null : prior.audience());
    String copy = field(payload, "copyText", 600, prior == null ? null : prior.copyText());
    Long budget =
        payload.has("budgetMinor")
            ? budget(payload.get("budgetMinor"))
            : prior == null ? null : prior.budgetMinor();
    Instant starts =
        payload.has("starts")
            ? optionalDate(payload.get("starts"), false)
            : prior == null ? null : prior.startsAt();
    Instant ends =
        payload.has("ends")
            ? optionalDate(payload.get("ends"), true)
            : prior == null ? null : prior.endsAt();
    window(starts, ends);
    CampaignSnapshot snapshot =
        new CampaignSnapshot(
            id == null ? UUID.randomUUID().toString() : id,
            prior == null ? 0 : prior.version(),
            name,
            objective,
            audience,
            copy,
            prior == null ? null : prior.channel(),
            prior == null ? "CNY" : prior.currency(),
            budget,
            starts,
            ends,
            prior == null ? "draft" : prior.state());
    ArrayNode items = mapper.createArrayNode();
    campaignDiff(items, snapshot.campaignId(), "name", prior == null ? null : prior.name(), name);
    campaignDiff(
        items,
        snapshot.campaignId(),
        "objective",
        prior == null ? null : prior.objective(),
        objective);
    campaignDiff(
        items,
        snapshot.campaignId(),
        "audience",
        prior == null ? null : prior.audience(),
        audience);
    campaignDiff(
        items, snapshot.campaignId(), "copy_text", prior == null ? null : prior.copyText(), copy);
    campaignDiff(
        items, snapshot.campaignId(), "budget", prior == null ? null : prior.budgetMinor(), budget);
    campaignDiff(
        items, snapshot.campaignId(), "starts", prior == null ? null : prior.startsAt(), starts);
    campaignDiff(items, snapshot.campaignId(), "ends", prior == null ? null : prior.endsAt(), ends);
    if (items.isEmpty()) {
      throw invalid("Campaign proposal has no changes");
    }
    return new PreparedOperation(snapshot.currency(), items, mapper.valueToTree(snapshot));
  }

  @Transactional(
      isolation = Isolation.READ_COMMITTED,
      noRollbackFor = {MerchantProductOperationException.class, MerchantException.class})
  public JsonNode apply(String kind, JsonNode stored, String changeId) {
    return switch (kind) {
      case "PROMOTION" -> applyPromotion(read(stored, PromotionSnapshot.class), changeId);
      case "CAMPAIGN" -> applyCampaign(read(stored, CampaignSnapshot.class), changeId);
      default -> throw new IllegalStateException("Stored marketing change has an invalid kind");
    };
  }

  private JsonNode applyPromotion(PromotionSnapshot snapshot, String changeId) {
    promotionTime(snapshot, changeId);
    if (snapshot.products().isEmpty()
        || snapshot.products().size() > 25
        || snapshot.discountBasisPoints() < 1
        || snapshot.discountBasisPoints() > 5000) {
      throw new IllegalStateException("Stored promotion bounds are invalid");
    }
    Set<String> unique = new HashSet<>();
    // Match the product-then-family order used by other approved retail operations.
    for (ProductTarget expected :
        snapshot.products().stream()
            .sorted(Comparator.comparing(ProductTarget::productId))
            .toList()) {
      if (!unique.add(expected.productId())
          || expected.targetPriceMinor()
              != discounted(expected.priceMinor(), snapshot.discountBasisPoints())) {
        throw new IllegalStateException("Stored promotion prices are invalid");
      }
      ProductTarget current;
      try {
        current = product(expected.productId(), true);
      } catch (MerchantException unavailable) {
        throw rejected("NOT_ORDERABLE", expected.productId());
      }
      if (current == null
          || current.version() != expected.version()
          || current.priceMinor() != expected.priceMinor()) {
        throw rejected("VERSION_CONFLICT", expected.productId());
      }
    }
    for (FamilyTarget expected :
        snapshot.families().stream()
            .sorted(Comparator.comparing(FamilyTarget::familyId))
            .toList()) {
      FamilyTarget current = family(expected.familyId(), true);
      if (current == null
          || current.version() != expected.version()
          || !current.members().equals(expected.members())) {
        throw rejected("FAMILY_VERSION_CONFLICT", expected.familyId());
      }
    }
    try {
      // Waiting for product/family locks may consume the remaining operating window.
      Instant now = promotionTime(snapshot, changeId);
      var changes =
          publication.changePrices(
              snapshot.products().stream()
                  .map(
                      target ->
                          new PriceChange(
                              target.productId(), target.version(), target.targetPriceMinor()))
                  .toList(),
              snapshot.currency());
      String promotionId = repository.insertPromotion(snapshot, changes, changeId, now);
      return mapper.valueToTree(
          Map.of(
              "promotionId",
              promotionId,
              "appliedAt",
              now,
              "priceRestoresAutomatically",
              false,
              "changes",
              changes));
    } catch (ProductPriceChangeException rejection) {
      throw rejected(rejection.reason().name(), rejection.productId());
    }
  }

  private Instant promotionTime(PromotionSnapshot snapshot, String changeId) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    if (now.isBefore(snapshot.startsAt())) {
      throw new MerchantException(
          409, "promotion_not_started", "Promotion has not started; proposal remains prepared");
    }
    if (!now.isBefore(snapshot.endsAt())) {
      throw rejected("PROMOTION_EXPIRED", changeId);
    }
    return now;
  }

  private JsonNode applyCampaign(CampaignSnapshot snapshot, String changeId) {
    if (snapshot.budgetMinor() != null
        && (snapshot.budgetMinor() < 0 || snapshot.budgetMinor() > 1_000_000)) {
      throw new IllegalStateException("Stored campaign budget is invalid");
    }
    Campaign current = repository.campaign(snapshot.campaignId(), true).orElse(null);
    if ((snapshot.expectedVersion() == 0 && current != null)
        || (snapshot.expectedVersion() != 0
            && (current == null || current.version() != snapshot.expectedVersion()))) {
      throw rejected("CAMPAIGN_VERSION_CONFLICT", snapshot.campaignId());
    }
    Campaign result =
        repository.writeCampaign(
            snapshot, changeId, clock.instant().truncatedTo(ChronoUnit.MICROS));
    return mapper.valueToTree(
        Map.of("campaignId", result.campaignId(), "version", result.version(), "campaign", result));
  }

  private ProductTarget product(String id, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT product_id,name,price_minor,currency,publication_version,publication_state,available FROM product WHERE product_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (row, index) -> {
              if (!"PUBLISHED".equals(row.getString("publication_state"))
                  || !row.getBoolean("available")
                  || row.getLong("price_minor") < 1) {
                throw new MerchantException(
                    409,
                    "product_not_editable",
                    "Promotion target must be a published available positive-price SKU");
              }
              return new ProductTarget(
                  row.getString("product_id"),
                  row.getString("name"),
                  row.getLong("price_minor"),
                  0,
                  row.getString("currency"),
                  row.getLong("publication_version"));
            },
            id);
    if (rows.isEmpty()) {
      return null;
    }
    ProductTarget target = rows.getFirst();
    if (!jdbc.queryForList(
            "SELECT activity_id FROM seckill_activity WHERE product_id=? LIMIT 1",
            String.class,
            target.productId())
        .isEmpty()) {
      throw new MerchantException(
          409, "seckill_product", "Promotion targets cannot be referenced by seckill");
    }
    return target;
  }

  private FamilyTarget family(String id, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT family_id,metadata_version FROM retail_product_family WHERE family_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (row, index) ->
                new FamilyTarget(
                    row.getString("family_id"), row.getLong("metadata_version"), List.of()),
            id);
    if (rows.isEmpty()) {
      return null;
    }
    FamilyTarget family = rows.getFirst();
    List<String> members =
        jdbc.queryForList(
            "SELECT product_id FROM retail_product_metadata WHERE family_id=? ORDER BY product_id LIMIT 26",
            String.class,
            family.familyId());
    return new FamilyTarget(family.familyId(), family.version(), members);
  }

  private void campaignDiff(
      ArrayNode items, String target, String field, Object before, Object after) {
    if (!java.util.Objects.equals(before, after)) {
      items.add(item(target, field, before, after));
    }
  }

  private ObjectNode item(String target, String field, Object before, Object after) {
    ObjectNode item = mapper.createObjectNode();
    item.put("target", target);
    item.put("field", field);
    item.set("before", mapper.valueToTree(before));
    item.set("after", mapper.valueToTree(after));
    return item;
  }

  private <T> T read(JsonNode stored, Class<T> type) {
    try {
      return mapper.treeToValue(stored, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored marketing operation is invalid", exception);
    }
  }

  private static void shape(JsonNode node, Set<String> allowed, Set<String> required) {
    if (node == null
        || !node.isObject()
        || required.stream().anyMatch(name -> !node.hasNonNull(name))) {
      throw invalid("Marketing payload is missing required fields");
    }
    node.fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name)) {
                throw invalid("Unexpected marketing payload field: " + name);
              }
            });
  }

  private static String field(JsonNode payload, String name, int maximum, String prior) {
    return payload.has(name) ? optionalText(payload.get(name), maximum, name) : prior;
  }

  private static String optionalText(JsonNode value, int maximum, String field) {
    return value == null || value.isNull() ? null : text(value, maximum, field);
  }

  private static String text(JsonNode value, int maximum, String field) {
    if (value == null
        || !value.isTextual()
        || value.textValue().isBlank()
        || value.textValue().length() > maximum) {
      throw invalid(field + " must be bounded nonblank text");
    }
    return value.textValue();
  }

  private static Long budget(JsonNode value) {
    if (value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0
        || value.longValue() > 1_000_000) {
      throw invalid("Campaign budget must be integer minor units from zero to 1000000");
    }
    return value.longValue();
  }

  private static int discount(JsonNode value) {
    if (!value.isNumber()) {
      throw invalid("Promotion discount must be numeric");
    }
    try {
      int points = value.decimalValue().movePointRight(2).intValueExact();
      if (points < 1 || points > 5000) {
        throw invalid(
            "Promotion discount must be positive, at most 50%, with at most two decimal places");
      }
      return points;
    } catch (ArithmeticException exception) {
      throw invalid("Promotion discount must have at most two decimal places");
    }
  }

  private static long discounted(long price, int points) {
    long result =
        BigDecimal.valueOf(price)
            .multiply(BigDecimal.valueOf(10_000L - points))
            .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
            .longValueExact();
    if (result < 1 || result >= price) {
      throw invalid(
          "Promotion must reduce every target by at least one minor unit without making it free");
    }
    return result;
  }

  private static Instant optionalDate(JsonNode value, boolean end) {
    return value.isNull() ? null : date(value, end);
  }

  private static Instant date(JsonNode value, boolean end) {
    String encoded = text(value, 40, end ? "ends" : "starts");
    try {
      Instant parsed;
      if (encoded.matches("\\d{4}-\\d{2}-\\d{2}")) {
        LocalDate day = LocalDate.parse(encoded);
        // Calendar end dates include that operating day; timestamp ends remain exclusive.
        parsed = (end ? day.plusDays(1) : day).atStartOfDay(STORE_ZONE).toInstant();
      } else {
        parsed = OffsetDateTime.parse(encoded).toInstant();
      }
      if (parsed.isBefore(Instant.parse("1970-01-01T00:00:00Z"))
          || parsed.isAfter(Instant.parse("9999-12-30T00:00:00Z"))) {
        throw invalid("Marketing date is outside the persistence range");
      }
      return parsed.truncatedTo(ChronoUnit.MICROS);
    } catch (DateTimeException exception) {
      throw invalid("Marketing dates require ISO calendar dates or timestamps with an offset");
    }
  }

  private static void window(Instant start, Instant end) {
    if (start != null && end != null && !start.isBefore(end)) {
      throw invalid("Marketing window must have starts before ends");
    }
  }

  private static MerchantException invalid(String message) {
    return new MerchantException(400, "validation", message);
  }

  private static MerchantProductOperationException rejected(String reason, String target) {
    return new MerchantProductOperationException(reason, target);
  }
}
