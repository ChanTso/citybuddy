package io.citybuddy.commerce.retail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.faq.FaqKnowledgeEventCodec;
import io.citybuddy.commerce.faq.FaqPublicationService;
import io.citybuddy.commerce.faq.FaqRepository;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.DeliveryOption;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.EstimateItem;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.EstimateRequest;
import io.citybuddy.commerce.shopping.ShoppingPreferencesConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(
    classes = RetailFactsIntegrationTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RetailFactsIntegrationTest {
  private static final String RULES =
      """
      {"standard":{"feeMinor":599,"freeOverMinor":4900,"minBusinessDays":3,"maxBusinessDays":5},
       "express":{"feeMinor":999,"memberFreeOverMinor":4900,"businessDays":2},
       "freight":{"feeMinor":2900,"minBusinessDays":5,"maxBusinessDays":7,
                  "categories":["office-electronics","fitness"],"unitPriceOverMinor":35000},
       "pickup":{"location":"ShopMate 上海演示门店（徐汇区）","opensAt":"09:00","closesAt":"21:00",
                 "preparationMinutes":120}}
      """;

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({RetailFactsConfiguration.class, ShoppingPreferencesConfiguration.class})
  static class Application {
    @Bean
    FaqRepository faqRepository(JdbcTemplate jdbc) {
      return new FaqRepository(jdbc);
    }

    @Bean
    FaqPublicationService faqPublicationService(FaqRepository repository, ObjectMapper mapper) {
      return new FaqPublicationService(
          repository, new FaqKnowledgeEventCodec(mapper), Clock.systemUTC());
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    registry.add("citybuddy.catalog.enabled", () -> "true");
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private RetailPolicyRepository policies;
  @MockitoSpyBean private RetailFulfillmentRepository repository;
  @Autowired private RetailFulfillmentService service;
  @Autowired private FaqPublicationService faqs;
  private Connection fixtureConnection;
  private JdbcTemplate fixture;
  private List<Map<String, Object>> originalConfig;
  private String prefix;
  private final List<String> skuIds = new ArrayList<>();
  private final List<String> faqIds = new ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    prefix = "rf-" + UUID.randomUUID().toString().substring(0, 8);
    fixtureConnection =
        DriverManager.getConnection(
            required("CATALOG_MYSQL_URL"), "bootstrap_admin", required("MYSQL_BOOTSTRAP_PASSWORD"));
    fixture = new JdbcTemplate(new SingleConnectionDataSource(fixtureConnection, true));
    fixture.execute("SET ROLE 'bootstrap_grant_role'");
    originalConfig =
        fixture.queryForList(
            "SELECT config_version,currency,time_zone,rules,updated_at"
                + " FROM retail_fulfillment_config WHERE config_id='default'");
    fixture.update(
        """
        INSERT INTO retail_fulfillment_config(config_id,config_version,currency,time_zone,rules)
        VALUES ('default',7,'CNY','Asia/Shanghai',CAST(? AS JSON))
        ON DUPLICATE KEY UPDATE config_version=7,currency='CNY',time_zone='Asia/Shanghai',rules=CAST(? AS JSON)
        """,
        RULES,
        RULES);
    fixture.update(
        """
        INSERT INTO crm_profile(user_subject,display_name,loyalty_tier,default_location,preferences)
        VALUES (?, 'Retail member','MEMBER','上海市徐汇区','{}')
        """,
        prefix);
  }

  @AfterEach
  void cleanup() throws Exception {
    if (fixtureConnection == null) {
      return;
    }
    try {
      for (String id : faqIds) {
        fixture.update(
            "DELETE FROM commerce_outbox WHERE aggregate_type='FAQ' AND aggregate_id=?", id);
        fixture.update("DELETE FROM faq_publication_command WHERE faq_id=?", id);
        fixture.update("DELETE FROM faq_draft_command WHERE faq_id=?", id);
        fixture.update("DELETE FROM faq_source WHERE faq_id=?", id);
      }
      for (String id : skuIds) {
        // Shared catalog suites enumerate published rows; completed fixtures must leave that set.
        fixture.update(
            "UPDATE product SET publication_state='UNPUBLISHED' WHERE product_id=BINARY ?", id);
      }
      fixture.update("DELETE FROM crm_profile WHERE user_subject=BINARY ?", prefix);
      if (originalConfig != null && originalConfig.isEmpty()) {
        fixture.update("DELETE FROM retail_fulfillment_config WHERE config_id='default'");
      } else if (originalConfig != null) {
        var previous = originalConfig.getFirst();
        fixture.update(
            "INSERT INTO retail_fulfillment_config(config_id,config_version,currency,time_zone,rules,updated_at)"
                + " VALUES ('default',?,?,?,CAST(? AS JSON),?) ON DUPLICATE KEY UPDATE"
                + " config_version=VALUES(config_version),currency=VALUES(currency),time_zone=VALUES(time_zone),"
                + " rules=VALUES(rules),updated_at=VALUES(updated_at)",
            previous.get("config_version"),
            previous.get("currency"),
            previous.get("time_zone"),
            previous.get("rules"),
            previous.get("updated_at"));
      }
    } finally {
      fixtureConnection.close();
    }
  }

  @Test
  void policySearchReadsPublishedContentAfterANewDraftAndScoresLiteralTerms() {
    String id = "retail-policy-" + prefix;
    String marker = prefix + "%_!";
    publish(
        id,
        "Shipping " + marker,
        "Standard delivery costs 599 minor units; free strictly over 4900.");
    faqs.saveDraft(id, "Unpublished replacement", "draftsecret" + prefix, 1);
    var result = policies.search(marker);
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().policyId()).isEqualTo(id);
    assertThat(result.getFirst().title()).isEqualTo("Shipping " + marker);
    assertThat(result.getFirst().content()).contains("599", "4900");
    assertThat(result.getFirst().publicationVersion()).isEqualTo(1);
    assertThat(result.getFirst().publishedAt()).isNotNull();
    assertThat(policies.search("draftsecret" + prefix)).isEmpty();

    String unpublished = "retail-guide-" + prefix;
    faqIds.add(unpublished);
    faqs.saveDraft(unpublished, marker, "Not published", 0);
    String outside = "private-" + prefix;
    publish(outside, marker, "An unrelated internal FAQ");
    assertThat(policies.search(marker))
        .extracting(RetailPolicyModels.Policy::policyId)
        .containsExactly(id);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM faq_publication_command WHERE faq_id=?", Long.class, id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type='FAQ' AND aggregate_id=?",
                Long.class,
                id))
        .isEqualTo(1);
  }

  @Test
  void boundedPolicyRankingPrefersTitleMatchesAndReturnsAtMostThree() {
    String marker = prefix + "rank";
    for (int index = 0; index < 4; index++) {
      publish(
          "retail-guide-" + prefix + "-" + index,
          index == 3 ? marker : "Body-only answer " + index,
          index == 3 ? "A title match" : marker);
    }
    assertThat(policies.search(marker))
        .extracting(RetailPolicyModels.Policy::policyId)
        .containsExactly(
            "retail-guide-" + prefix + "-3",
            "retail-guide-" + prefix + "-0",
            "retail-guide-" + prefix + "-1");
    assertThatThrownBy(() -> policies.search(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policies.search("one two three four five six seven eight nine"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void quoteUsesExactMembershipActualQuantityCanonicalSkuAndInheritedCategory() {
    String sku = product("sku", 2450, "CNY", 24);
    var atThreshold = service.estimate(prefix, request(sku, 2));
    assertThat(atThreshold.itemSubtotalMinor()).isEqualTo(4900);
    assertThat(option(atThreshold.options(), "EXPRESS").feeMinor()).isEqualTo(999);
    jdbc.update(
        "UPDATE product SET price_minor=2451,publication_version=publication_version+1 WHERE product_id=?",
        sku);
    var member = service.estimate(prefix, request(sku.toUpperCase(Locale.ROOT), 2));
    assertThat(member.itemSubtotalMinor()).isEqualTo(4902);
    assertThat(member.items().getFirst().productId()).isEqualTo(sku);
    assertThat(member.items().getFirst().productVersion()).isEqualTo(2);
    assertThat(option(member.options(), "EXPRESS").feeMinor()).isZero();
    assertThat(
            option(
                    service.estimate(prefix.toUpperCase(Locale.ROOT), request(sku, 2)).options(),
                    "EXPRESS")
                .feeMinor())
        .isEqualTo(999);
    assertThatThrownBy(
            () ->
                service.estimate(
                    prefix,
                    new EstimateRequest(
                        List.of(
                            new EstimateItem(sku, 1),
                            new EstimateItem(sku.toUpperCase(Locale.ROOT), 1)))))
        .isInstanceOfSatisfying(
            RetailFulfillmentException.class,
            exception -> assertThat(exception.category()).isEqualTo("duplicate_sku"));
    String family = prefix + "-family";
    fixture.update(
        """
        INSERT INTO retail_product_family(family_id,name,description,content,options)
        VALUES (?,'Fitness family','Retail fixture','{"category":"fitness"}',
                '[{"name":"size","values":["M"]}]')
        """,
        family);
    fixture.update(
        "INSERT INTO retail_product_metadata(product_id,family_id,content,option_values) VALUES (?,?,'{}','{\"size\":\"M\"}')",
        sku,
        family);
    jdbc.update("UPDATE product SET price_minor=35001 WHERE product_id=?", sku);
    assertThat(option(service.estimate(prefix, request(sku, 1)).options(), "FREIGHT").feeMinor())
        .isEqualTo(2900);
    assertThatThrownBy(() -> service.estimate(prefix, request(family, 1)))
        .isInstanceOf(RetailFulfillmentException.class);
    fixture.update(
        "UPDATE retail_product_metadata SET content='{\"category\":\"books\"}' WHERE product_id=?",
        sku);
    assertThat(service.estimate(prefix, request(sku, 1)).options())
        .noneMatch(option -> option.code().equals("FREIGHT"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_profile WHERE BINARY user_subject=BINARY ?",
                Long.class,
                prefix.toUpperCase(Locale.ROOT)))
        .isZero();
  }

  @Test
  void oneEstimateCannotMixMembershipAndSkuFactsFromDifferentCommittedSnapshots() {
    String sku = product("snapshot", 4901, "CNY", 24);
    doAnswer(
            invocation -> {
              Object config = invocation.callRealMethod();
              // This separate fixture connection commits after the quote's first consistent read.
              fixture.update(
                  "UPDATE crm_profile SET loyalty_tier='NONE' WHERE user_subject=BINARY ?", prefix);
              fixture.update(
                  "UPDATE product SET price_minor=6000,publication_version=2 WHERE product_id=?",
                  sku);
              return config;
            })
        .doCallRealMethod()
        .when(repository)
        .configuration();

    var first = service.estimate(prefix, request(sku, 1));
    assertThat(first.itemSubtotalMinor()).isEqualTo(4901);
    assertThat(first.items().getFirst().productVersion()).isEqualTo(1);
    assertThat(option(first.options(), "EXPRESS").feeMinor()).isZero();
    var next = service.estimate(prefix, request(sku, 1));
    assertThat(next.itemSubtotalMinor()).isEqualTo(6000);
    assertThat(next.items().getFirst().productVersion()).isEqualTo(2);
    assertThat(option(next.options(), "EXPRESS").feeMinor()).isEqualTo(999);
  }

  @Test
  void entireCartIsQuotedAndInvalidLinesNeverYieldAPartialSuccess() {
    List<EstimateItem> items = new ArrayList<>();
    for (int index = 0; index < 100; index++) {
      items.add(new EstimateItem(product("sku-" + index, 100, "CNY", 24), 2));
    }
    var request = new EstimateRequest(items);
    var result = service.estimate(prefix, request);
    assertThat(result.items()).hasSize(100);
    assertThat(result.itemSubtotalMinor()).isEqualTo(20000);
    String last = items.getLast().productId();
    jdbc.update("UPDATE product SET stock_quantity=1 WHERE product_id=?", last);
    assertThatThrownBy(() -> service.estimate(prefix, request))
        .isInstanceOf(RetailFulfillmentException.class);
    jdbc.update("UPDATE product SET stock_quantity=24,currency='USD' WHERE product_id=?", last);
    assertThatThrownBy(() -> service.estimate(prefix, request))
        .isInstanceOfSatisfying(
            RetailFulfillmentException.class,
            exception -> assertThat(exception.category()).isEqualTo("unsupported_currency"));
    jdbc.update("UPDATE product SET currency='CNY',price_minor=0 WHERE product_id=?", last);
    assertThatThrownBy(() -> service.estimate(prefix, request))
        .isInstanceOf(RetailFulfillmentException.class);
  }

  @Test
  void malformedOrMissingStoredRulesAreServiceErrorsNotFreeDelivery() {
    fixture.update(
        "UPDATE retail_fulfillment_config SET rules=JSON_SET(rules,'$.standard.feeMinor',-1) WHERE config_id='default'");
    assertThatThrownBy(repository::configuration).isInstanceOf(IllegalStateException.class);
    fixture.update(
        "UPDATE retail_fulfillment_config SET rules=JSON_SET(CAST(? AS JSON),'$.standard.extra',true) WHERE config_id='default'",
        RULES);
    assertThatThrownBy(repository::configuration).isInstanceOf(IllegalStateException.class);
    fixture.update(
        "UPDATE retail_fulfillment_config SET rules=JSON_SET(CAST(? AS JSON),'$.standard.feeMinor','599') WHERE config_id='default'",
        RULES);
    assertThatThrownBy(repository::configuration).isInstanceOf(IllegalStateException.class);
    fixture.update("DELETE FROM retail_fulfillment_config WHERE config_id='default'");
    assertThatThrownBy(repository::configuration).isInstanceOf(IllegalStateException.class);
  }

  private String product(String suffix, long price, String currency, long stock) {
    String id = prefix + "-" + suffix;
    jdbc.update(
        """
        INSERT INTO product(product_id,name,description,price_minor,currency,stock_quantity,available,publication_state,publication_version)
        VALUES (?,'Retail facts SKU','Isolated facts fixture',?,?,?,TRUE,'PUBLISHED',1)
        """,
        id,
        price,
        currency,
        stock);
    skuIds.add(id);
    return id;
  }

  private void publish(String id, String title, String content) {
    faqIds.add(id);
    faqs.saveDraft(id, title, content, 0);
    faqs.publish(
        new FaqPublicationService.PublicationCommand(id, id, UUID.randomUUID().toString(), 1, 0));
  }

  private static EstimateRequest request(String id, int quantity) {
    return new EstimateRequest(List.of(new EstimateItem(id, quantity)));
  }

  private static DeliveryOption option(List<DeliveryOption> options, String code) {
    return options.stream().filter(option -> option.code().equals(code)).findFirst().orElseThrow();
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing integration environment variable: " + name);
    }
    return value;
  }
}
