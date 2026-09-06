package io.citybuddy.commerce.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.catalog.ProductCache;
import io.citybuddy.commerce.catalog.ProductPublicationService;
import io.citybuddy.commerce.catalog.ProductRepository;
import io.citybuddy.commerce.merchant.MerchantProductOperations.PreparedOperation;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(
    classes = MerchantProductOperationsIntegrationTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MerchantProductOperationsIntegrationTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class Application {
    @Bean
    ProductRepository productRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
      return new ProductRepository(jdbc, mapper);
    }

    @Bean
    ProductCache productCache() {
      return mock(ProductCache.class);
    }

    @Bean
    ProductPublicationService publication(ProductRepository repository, ProductCache cache) {
      return new ProductPublicationService(repository, cache);
    }

    @Bean
    MerchantProductOperations operations(
        JdbcTemplate jdbc, ObjectMapper mapper, ProductPublicationService publication) {
      return new MerchantProductOperations(jdbc, mapper, publication);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private MerchantProductOperations operations;
  @Autowired private ProductPublicationService publication;
  @Autowired private ProductCache cache;
  @Autowired private PlatformTransactionManager transactionManager;
  private String prefix;
  private Connection connection;
  private JdbcTemplate fixture;
  private final List<String> products = new ArrayList<>();
  private final List<String> activities = new ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    prefix = "mop-" + UUID.randomUUID().toString().substring(0, 8);
    connection =
        DriverManager.getConnection(
            required("CATALOG_MYSQL_URL"), "bootstrap_admin", required("MYSQL_BOOTSTRAP_PASSWORD"));
    fixture = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    fixture.execute("SET ROLE 'bootstrap_grant_role'");
    clearInvocations(cache);
  }

  @AfterEach
  void cleanup() throws Exception {
    try {
      for (String id : activities) {
        fixture.update("DELETE FROM seckill_activity WHERE activity_id=?", id);
      }
      for (String id : products) {
        // Retain transaction evidence while keeping completed fixtures out of shared catalog reads.
        jdbc.update("UPDATE product SET publication_state='UNPUBLISHED' WHERE product_id=?", id);
      }
    } finally {
      if (connection != null) {
        connection.close();
      }
    }
  }

  @Test
  void restockAddsToLockedCurrentStockWithoutOverwritingAnInterveningConsumption() {
    String sku = product("a", 9, false, "PUBLISHED");
    PreparedOperation prepared =
        inventory(List.of(Map.of("listingId", sku, "action", "restock", "quantity", 10)));
    assertThat(number(sku, "stock_quantity")).isEqualTo(9);
    assertThat(events(sku)).isZero();
    jdbc.update("UPDATE product SET stock_quantity=stock_quantity-3 WHERE product_id=?", sku);
    long generation = generation();
    JsonNode result = operations.apply("INVENTORY_ACTION", prepared.snapshot());
    assertThat(number(sku, "stock_quantity")).isEqualTo(16);
    assertThat(number(sku, "price_minor")).isEqualTo(1000);
    assertThat(number(sku, "publication_version")).isEqualTo(2);
    assertThat(number(sku, "available")).isZero();
    assertThat(result.path("changes").get(0).path("beforeStock").asLong()).isEqualTo(6);
    assertThat(result.path("changes").get(0).path("afterStock").asLong()).isEqualTo(16);
    assertThat(events(sku)).isEqualTo(1);
    assertThat(generation()).isEqualTo(generation + 1);
    verify(cache).evict(sku);
  }

  @Test
  void familyContentUpdatesSharedFieldsWithoutPublishingDraftsOrChangingTradeFields() {
    String a = product("a", 9, true, "PUBLISHED");
    String b = product("b", 0, false, "DRAFT");
    String family = family(List.of(a, b));
    fixture.update(
        "UPDATE retail_product_metadata SET content='{\"longDescription\":\"Old override\",\"category\":\"old\"}' WHERE product_id=?",
        a);
    var prepared =
        listing(
            family,
            Map.of(
                "title",
                "Shared new title",
                "short_description",
                "Shared copy",
                "long_description",
                "Shared full description",
                "category",
                "fitness"));
    long generation = generation();
    var result = operations.apply("LISTING_UPDATE", prepared.snapshot());
    for (String id : List.of(a, b)) {
      assertThat(
              jdbc.queryForObject("SELECT name FROM product WHERE product_id=?", String.class, id))
          .isEqualTo("Shared new title");
      assertThat(
              jdbc.queryForObject(
                  "SELECT description FROM product WHERE product_id=?", String.class, id))
          .isEqualTo("Shared copy");
      assertThat(number(id, "price_minor")).isEqualTo(1000);
      assertThat(number(id, "publication_version")).isEqualTo(2);
    }
    assertThat(number(a, "stock_quantity")).isEqualTo(9);
    assertThat(number(b, "stock_quantity")).isZero();
    assertThat(number(b, "available")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT publication_state FROM product WHERE product_id=?", String.class, b))
        .isEqualTo("DRAFT");
    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_CONTAINS_PATH(content,'one','$.category','$.longDescription') FROM retail_product_metadata WHERE product_id=?",
                Integer.class,
                a))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(content,'$.category')) FROM retail_product_family WHERE family_id=?",
                String.class,
                family))
        .isEqualTo("fitness");
    assertThat(result.path("events")).hasSize(2);
    assertThat(generation()).isEqualTo(generation + 2);
    verify(cache).evict(a);
    verify(cache).evict(b);
  }

  @Test
  void aLateVersionConflictRejectsEverySkuBeforeAnyWriteEvenWhenParentCommitsTheRejection() {
    String a = product("a", 9, true, "PUBLISHED");
    String b = product("b", 10, true, "PUBLISHED");
    var prepared =
        inventory(
            List.of(
                Map.of("listingId", a, "action", "restock", "quantity", 10),
                Map.of("listingId", b, "action", "pause")));
    jdbc.update("UPDATE product SET publication_version=2 WHERE product_id=?", b);
    TransactionTemplate parent = new TransactionTemplate(transactionManager);
    parent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    parent.executeWithoutResult(
        status ->
            assertThatThrownBy(() -> operations.apply("INVENTORY_ACTION", prepared.snapshot()))
                .isInstanceOfSatisfying(
                    MerchantProductOperationException.class,
                    exception -> assertThat(exception.reason()).isEqualTo("VERSION_CONFLICT")));
    assertThat(number(a, "stock_quantity")).isEqualTo(9);
    assertThat(number(a, "publication_version")).isEqualTo(1);
    assertThat(number(b, "available")).isEqualTo(1);
    assertThat(events(a) + events(b)).isZero();
    verifyNoInteractions(cache);
  }

  @Test
  void familyMembershipAndPrivateFactsVersionsArePartOfTheApprovedSnapshot() {
    String a = product("a", 9, true, "PUBLISHED");
    String b = product("b", 9, true, "PUBLISHED");
    String family = family(List.of(a));
    var prepared = inventory(List.of(Map.of("listingId", family, "action", "pause")));
    fixture.update(
        "INSERT INTO retail_product_metadata(product_id,family_id,content,option_values) VALUES (?,?,'{}','{\"size\":\"L\"}')",
        b,
        family);
    assertThatThrownBy(() -> operations.apply("INVENTORY_ACTION", prepared.snapshot()))
        .isInstanceOfSatisfying(
            MerchantProductOperationException.class,
            exception -> assertThat(exception.reason()).isEqualTo("FAMILY_VERSION_CONFLICT"));
    assertThat(number(a, "available")).isEqualTo(1);
    facts(a);
    var second = listing(a, Map.of("material", "Cotton"));
    fixture.update(
        "UPDATE retail_product_operations SET facts_version=facts_version+1 WHERE product_id=?", a);
    assertThatThrownBy(() -> operations.apply("LISTING_UPDATE", second.snapshot()))
        .isInstanceOf(MerchantProductOperationException.class);
    assertThat(events(a)).isZero();
  }

  @Test
  void contentQualityChangesOnlyAfterActualMissingAttributesAreCompleted() {
    String sku = product("a", 9, true, "PUBLISHED");
    facts(sku);
    operations.apply(
        "LISTING_UPDATE", listing(sku, Map.of("short_description", "New description")).snapshot());
    assertThat(quality(sku)).isEqualTo("needs_work");
    assertThat(
            fixture.queryForObject(
                "SELECT facts_version FROM retail_product_operations WHERE product_id=?",
                Long.class,
                sku))
        .isEqualTo(1);
    operations.apply("LISTING_UPDATE", listing(sku, Map.of("material", "Cotton")).snapshot());
    assertThat(quality(sku)).isEqualTo("needs_work");
    assertThat(
            fixture.queryForObject(
                "SELECT JSON_LENGTH(missing_attributes) FROM retail_product_operations WHERE product_id=?",
                Integer.class,
                sku))
        .isEqualTo(1);
    operations.apply(
        "LISTING_UPDATE", listing(sku, Map.of("wall coverage", "2 square metres")).snapshot());
    assertThat(quality(sku)).isEqualTo("good");
    assertThat(
            fixture.queryForObject(
                "SELECT facts_version FROM retail_product_operations WHERE product_id=?",
                Long.class,
                sku))
        .isEqualTo(3);
    assertThat(
            fixture.queryForObject(
                "SELECT unit_cost_minor FROM retail_product_operations WHERE product_id=?",
                Long.class,
                sku))
        .isEqualTo(500);
    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_CONTAINS_PATH(content,'one','$.attributes.content_quality','$.attributes.unit_cost') FROM retail_product_metadata WHERE product_id=?",
                Integer.class,
                sku))
        .isZero();
  }

  @Test
  void variantSharedFieldsAndProtectedAttributesAreRejectedButItsSkuDescriptionIsWritable() {
    String sku = product("a", 9, true, "PUBLISHED");
    family(List.of(sku));
    assertThatThrownBy(() -> listing(sku, Map.of("short_description", "Not variant-owned")))
        .isInstanceOfSatisfying(
            MerchantException.class,
            exception -> assertThat(exception.category()).isEqualTo("shared_family_content"));
    fixture.update(
        "UPDATE retail_product_metadata SET content='{\"attributes\":{\"price\":\"100\",\"currency\":\"CNY\"}}' WHERE product_id=?",
        sku);
    for (String key : List.of("price", "currency", "content_quality", "stock", "availability")) {
      assertThatThrownBy(() -> listing(sku, Map.of(key, "changed")))
          .isInstanceOf(MerchantException.class);
    }
    operations.apply("LISTING_UPDATE", listing(sku, Map.of("sku", "SKU-M")).snapshot());
    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(content,'$.attributes.sku')) FROM retail_product_metadata WHERE product_id=?",
                String.class,
                sku))
        .isEqualTo("SKU-M");
    assertThat(number(sku, "price_minor")).isEqualTo(1000);
  }

  @Test
  void optionDimensionsCannotBeEditedAsContentButPlainDescriptiveSizeCan() {
    String sku = product("variant", 9, true, "PUBLISHED");
    String family = family(List.of(sku));
    fixture.update(
        "UPDATE retail_product_family SET content=JSON_SET(content,'$.attributes',CAST(? AS JSON)),options=CAST(? AS JSON) WHERE family_id=?",
        "{\"size\":\"M\",\"SiZe\":\"M\",\"width\":\"standard\"}",
        "[{\"name\":\"size\",\"values\":[\"M\",\"L\"]},{\"name\":\"material\",\"values\":[\"Cotton\",\"Linen\"]}]",
        family);
    fixture.update(
        "UPDATE retail_product_metadata SET content=CAST(? AS JSON),option_values=CAST(? AS JSON) WHERE product_id=?",
        "{\"attributes\":{\"size\":\"M\",\"SiZe\":\"M\",\"width\":\"standard\"}}",
        "{\"size\":\"M\",\"width\":\"standard\"}",
        sku);
    for (String target : List.of(family, sku)) {
      for (String dimension : List.of("size", "SiZe", "width", "material")) {
        assertThatThrownBy(() -> listing(target, Map.of(dimension, "L")))
            .isInstanceOfSatisfying(
                MerchantException.class,
                exception -> {
                  assertThat(exception.category()).isEqualTo("validation");
                  assertThat(exception.getMessage()).contains("Protected listing field");
                });
      }
    }
    String plain = product("plain", 5, true, "PUBLISHED");
    fixture.update(
        "INSERT INTO retail_product_metadata(product_id,content,option_values) VALUES (?,CAST(? AS JSON),'{}')",
        plain,
        "{\"attributes\":{\"size\":\"Compact\"}}");
    operations.apply("LISTING_UPDATE", listing(plain, Map.of("size", "Large")).snapshot());
    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(content,'$.attributes.size')) FROM retail_product_metadata WHERE product_id=?",
                String.class,
                plain))
        .isEqualTo("Large");
    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(option_values,'$.size')) FROM retail_product_metadata WHERE product_id=?",
                String.class,
                sku))
        .isEqualTo("M");
    assertThat(number(sku, "publication_version")).isEqualTo(1);
  }

  @Test
  void familyPauseAndActivateRespectStockWhileRestockNeverRemovesAnExplicitPause() {
    String a = product("a", 9, true, "PUBLISHED");
    String b = product("b", 0, true, "PUBLISHED");
    String family = family(List.of(a, b));
    operations.apply(
        "INVENTORY_ACTION",
        inventory(List.of(Map.of("listingId", family, "action", "pause"))).snapshot());
    assertThat(number(a, "available") + number(b, "available")).isZero();
    operations.apply(
        "INVENTORY_ACTION",
        inventory(List.of(Map.of("listingId", a, "action", "restock", "quantity", 1))).snapshot());
    assertThat(number(a, "available")).isZero();
    var activate = inventory(List.of(Map.of("listingId", family, "action", "activate")));
    JsonNode emptySkuCard = activate.items().get(1);
    assertThat(emptySkuCard.path("target").asText()).isEqualTo(b);
    assertThat(emptySkuCard.path("field").asText()).isEqualTo("available");
    assertThat(emptySkuCard.path("before").isBoolean()).isTrue();
    assertThat(emptySkuCard.path("before").booleanValue()).isFalse();
    assertThat(emptySkuCard.path("after").isBoolean()).isTrue();
    assertThat(emptySkuCard.path("after").booleanValue()).isTrue();
    operations.apply("INVENTORY_ACTION", activate.snapshot());
    assertThat(number(a, "available")).isEqualTo(1);
    assertThat(number(b, "available")).isEqualTo(1);
    assertThat(number(b, "stock_quantity")).isZero();
    assertThatThrownBy(
            () ->
                inventory(List.of(Map.of("listingId", family, "action", "restock", "quantity", 1))))
        .isInstanceOf(MerchantException.class);
    assertThatThrownBy(
            () ->
                inventory(
                    List.of(
                        Map.of("listingId", family, "action", "pause"),
                        Map.of("listingId", a, "action", "activate"))))
        .isInstanceOf(MerchantException.class);
  }

  @Test
  void unpublishedActivationAndLateSeckillReferencesCannotBecomeSuccessfulOperations() {
    String draft = product("draft", 9, true, "DRAFT");
    assertThatThrownBy(() -> inventory(List.of(Map.of("listingId", draft, "action", "activate"))))
        .isInstanceOfSatisfying(
            MerchantException.class,
            exception -> assertThat(exception.category()).isEqualTo("not_published"));
    String sku = product("a", 9, true, "PUBLISHED");
    var prepared = listing(sku, Map.of("short_description", "New copy"));
    String activity = prefix + "-activity";
    fixture.update(
        "INSERT INTO seckill_activity(activity_id,product_id,starts_at,ends_at,state,allocated_quota,projection_version)"
            + " VALUES (?,?,'2026-01-01','2026-01-02','CLOSED',1,1)",
        activity,
        sku);
    activities.add(activity);
    assertThatThrownBy(() -> operations.apply("LISTING_UPDATE", prepared.snapshot()))
        .isInstanceOfSatisfying(
            MerchantProductOperationException.class,
            exception -> assertThat(exception.reason()).isEqualTo("SECKILL_PRODUCT"));
    assertThat(events(sku)).isZero();
    assertThat(number(sku, "publication_version")).isEqualTo(1);
    assertThatThrownBy(() -> listing(sku, Map.of("title", "Cannot edit")))
        .isInstanceOf(MerchantException.class);
  }

  @Test
  void stockOverflowIsRejectedBeforeAnyProductOrEventWrite() {
    String sku = product("a", 9, true, "PUBLISHED");
    var prepared = inventory(List.of(Map.of("listingId", sku, "action", "restock", "quantity", 1)));
    jdbc.update("UPDATE product SET stock_quantity=? WHERE product_id=?", Long.MAX_VALUE, sku);
    assertThatThrownBy(() -> operations.apply("INVENTORY_ACTION", prepared.snapshot()))
        .isInstanceOfSatisfying(
            MerchantProductOperationException.class,
            exception -> assertThat(exception.reason()).isEqualTo("VALUE_OUT_OF_RANGE"));
    assertThat(number(sku, "publication_version")).isEqualTo(1);
    assertThat(events(sku)).isZero();
  }

  @Test
  void existingPricePublicationSupportsTwentyFiveItemsAndRejectsTwentySix() {
    List<ProductRepository.PriceChange> changes = new ArrayList<>();
    for (int index = 0; index < 25; index++) {
      changes.add(
          new ProductRepository.PriceChange(
              product("price-" + index, 9, true, "PUBLISHED"), 1, 1100));
    }
    long generation = generation();
    assertThat(publication.changePrices(changes, "CNY")).hasSize(25);
    assertThat(generation()).isEqualTo(generation + 25);
    for (ProductRepository.PriceChange change : changes) {
      assertThat(number(change.productId(), "price_minor")).isEqualTo(1100);
      assertThat(events(change.productId())).isEqualTo(1);
    }
    changes.add(
        new ProductRepository.PriceChange(product("price-26", 9, true, "PUBLISHED"), 1, 1100));
    assertThatThrownBy(() -> publication.changePrices(changes, "CNY"))
        .isInstanceOf(IllegalArgumentException.class);
    String family = family(changes.stream().map(ProductRepository.PriceChange::productId).toList());
    assertThatThrownBy(() -> inventory(List.of(Map.of("listingId", family, "action", "pause"))))
        .isInstanceOf(MerchantException.class);
  }

  private PreparedOperation listing(String id, Map<String, String> fields) {
    return operations.prepare(
        "LISTING_UPDATE", mapper.valueToTree(Map.of("listingId", id, "fields", fields)));
  }

  private PreparedOperation inventory(List<Map<String, Object>> items) {
    return operations.prepare("INVENTORY_ACTION", mapper.valueToTree(Map.of("items", items)));
  }

  private String product(String suffix, long stock, boolean available, String state) {
    String id = prefix + "-" + suffix;
    jdbc.update(
        "INSERT INTO product(product_id,name,description,price_minor,currency,stock_quantity,available,publication_state,publication_version)"
            + " VALUES (?,?,'Original description',1000,'CNY',?,?,?,1)",
        id,
        id + " name",
        stock,
        available,
        state);
    products.add(id);
    return id;
  }

  private String family(List<String> skus) {
    String id = prefix + "-family";
    fixture.update(
        "INSERT INTO retail_product_family(family_id,name,description,content,options) VALUES (?,'Family','Family description',"
            + "'{\"category\":\"home\",\"longDescription\":\"Family content\"}','[{\"name\":\"size\",\"values\":[\"M\",\"L\"]}]')",
        id);
    for (String sku : skus) {
      fixture.update(
          "INSERT INTO retail_product_metadata(product_id,family_id,content,option_values) VALUES (?,?,'{}','{\"size\":\"M\"}')",
          sku,
          id);
    }
    return id;
  }

  private void facts(String sku) {
    fixture.update(
        "INSERT INTO retail_product_operations(product_id,unit_cost_minor,low_stock_threshold,content_quality,missing_attributes,facts_version,observed_at,source_ref)"
            + " VALUES (?,500,5,'needs_work','[\"material\",\"wall coverage\"]',1,CURRENT_TIMESTAMP(6),?)",
        sku,
        prefix);
  }

  private long number(String id, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM product WHERE product_id=?", Long.class, id);
  }

  private String quality(String id) {
    return jdbc.queryForObject(
        "SELECT content_quality FROM retail_product_operations WHERE product_id=?",
        String.class,
        id);
  }

  private long events(String id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type='PRODUCT' AND aggregate_id=?",
        Long.class,
        id);
  }

  private long generation() {
    return jdbc.queryForObject(
        "SELECT COALESCE(MAX(publication_generation),0) FROM catalog_metadata", Long.class);
  }

  private static String required(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing integration variable " + key);
    }
    return value;
  }
}
