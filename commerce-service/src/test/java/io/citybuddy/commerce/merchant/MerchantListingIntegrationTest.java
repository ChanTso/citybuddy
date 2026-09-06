package io.citybuddy.commerce.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.citybuddy.commerce.identity.OboIdentityConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(
    classes = MerchantListingIntegrationTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MerchantListingIntegrationTest {
  private static final Instant AS_OF = Instant.parse("2026-09-07T01:00:00Z");
  private static final Instant START = Instant.parse("2026-08-07T16:00:00Z");
  private static final Instant END = Instant.parse("2026-09-06T16:00:00Z");

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({
    MerchantListingConfiguration.class,
    MerchantListingController.class,
    MerchantExceptionHandler.class,
    OboIdentityConfiguration.class
  })
  static class Application {
    @Bean
    Clock catalogClock() {
      return Clock.fixed(AS_OF, ZoneOffset.UTC);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    registry.add("citybuddy.merchant.enabled", () -> "true");
    registry.add("citybuddy.obo.enabled", () -> "true");
    registry.add("citybuddy.obo.issuer", () -> "https://identity.citybuddy.test");
    registry.add("citybuddy.obo.jwks-url", () -> required("IDENTITY_JWKS_URL"));
  }

  @Autowired private TestRestTemplate http;
  private JdbcTemplate fixture;
  private RSAPrivateKey key;
  private String prefix;
  private final List<String> products = new ArrayList<>();
  private final List<String> families = new ArrayList<>();
  private final List<Paid> orders = new ArrayList<>();
  private final List<String> refunds = new ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    prefix = "listing-it-" + UUID.randomUUID().toString().substring(0, 8);
    fixture =
        new JdbcTemplate(
            new DriverManagerDataSource(
                required("CATALOG_MYSQL_URL"), "root", required("MYSQL_BOOTSTRAP_PASSWORD")));
    String encoded =
        Files.readString(Path.of(required("CATALOG_TEST_SIGNING_PRIVATE_KEY_PATH")))
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    key =
        (RSAPrivateKey)
            KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
  }

  @AfterEach
  void removeOnlyOwnedFacts() {
    for (String refund : refunds) {
      fixture.update("DELETE FROM mock_refund WHERE refund_id=?", refund);
    }
    for (Paid paid : orders) {
      fixture.update("DELETE FROM mock_payment_attempt WHERE attempt_id=?", paid.attemptId());
      fixture.update("DELETE FROM standard_order WHERE order_id=?", paid.orderId());
    }
    for (String product : products) {
      fixture.update("DELETE FROM retail_product_operations WHERE product_id=?", product);
      fixture.update("DELETE FROM retail_product_metadata WHERE product_id=?", product);
      fixture.update("DELETE FROM product WHERE product_id=?", product);
    }
    for (String family : families) {
      fixture.update("DELETE FROM retail_product_family WHERE family_id=?", family);
    }
  }

  @Test
  void rootPaginationReachesBeyondOneHundredSkusAndIncludesPausedDraftAndFamilyDetails()
      throws Exception {
    for (int index = 0; index < 103; index++) {
      product(
          String.format("plain-%03d", index),
          1000,
          index == 0 ? 64 : 9,
          index != 0,
          index == 1 ? "DRAFT" : index == 2 ? "UNPUBLISHED" : "PUBLISHED");
    }
    String small = product("variant-small", 700, 0, true, "PUBLISHED");
    String large = product("variant-large", 1200, 8, true, "PUBLISHED");
    String family = family(small, large);
    facts(small, 400L, 5, "needs_work");
    List<String> ids = new ArrayList<>();
    Integer offset = 0;
    List<String> firstPage = null;
    while (offset != null) {
      JsonNode page =
          get("/internal/merchant/listings?query=" + prefix + "&limit=20&offset=" + offset)
              .getBody();
      List<String> found = page.path("items").findValuesAsText("id");
      assertThat(found).hasSizeLessThanOrEqualTo(20);
      ids.addAll(found);
      if (offset == 0) {
        firstPage = found;
      }
      offset = page.path("nextOffset").isNull() ? null : page.path("nextOffset").intValue();
    }
    assertThat(ids)
        .hasSize(104)
        .doesNotHaveDuplicates()
        .contains(family)
        .doesNotContain(small, large);
    assertThat(
            get("/internal/merchant/listings?query=" + prefix + "&limit=20")
                .getBody()
                .path("items")
                .findValuesAsText("id"))
        .isEqualTo(firstPage);
    JsonNode detail = listing(family);
    assertThat(detail.path("priceMinor").asLong()).isEqualTo(700);
    assertThat(detail.path("stockQuantity").asLong()).isEqualTo(8);
    assertThat(detail.path("status").asText()).isEqualTo("active");
    assertThat(detail.path("contentQuality").asText()).isEqualTo("needs_work");
    assertThat(detail.path("operations").isNull()).isTrue();
    assertThat(detail.path("variants").findValuesAsText("id")).containsExactly(small, large);
    assertThat(detail.path("variants").get(0).path("optionValues").path("size").asText())
        .isEqualTo("M");
    assertThat(listing(prefix + "-plain-000").path("status").asText()).isEqualTo("paused");
    assertThat(listing(prefix + "-plain-001").path("publicationState").asText()).isEqualTo("DRAFT");
    assertThat(listing(prefix + "-plain-002").path("publicationState").asText())
        .isEqualTo("UNPUBLISHED");
    assertThat(
            get("/internal/merchant/listings?query=" + prefix + "&status=paused")
                .getBody()
                .path("items")
                .size())
        .isEqualTo(2);
    assertThat(
            get("/internal/merchant/listings?query="
                    + prefix
                    + "&maxStock=8&currency=CNY&sort=price_asc")
                .getBody()
                .path("items")
                .get(0)
                .path("id")
                .asText())
        .isEqualTo(family);
  }

  @Test
  void privateFactsKeepUnknownCostNullAndAlertsUseActualSalesStockAndVisibility() throws Exception {
    String low = product("low", 1000, 4, true, "PUBLISHED");
    String slow = product("slow", 1000, 20, false, "PUBLISHED");
    String unknown = product("unknown", 1000, 0, true, "PUBLISHED");
    facts(low, 800L, 5, "needs_work");
    facts(slow, null, 5, "good");
    paid(low, 2, START.plusSeconds(10), "SUCCEEDED", true, prefix);
    JsonNode known = listing(low);
    assertThat(known.path("operations").path("unitCostMinor").asLong()).isEqualTo(800);
    assertThat(known.path("marginPct").decimalValue()).isEqualByComparingTo("20");
    assertThat(known.path("operations").path("missingAttributes").get(0).asText())
        .isEqualTo("material");
    assertThat(known.path("content").path("attributes").has("unitCostMinor")).isFalse();
    assertThat(listing(unknown).path("operations").isNull()).isTrue();
    assertThat(listing(unknown).path("marginPct").isNull()).isTrue();
    assertThat(listing(slow).path("operations").path("unitCostMinor").isNull()).isTrue();
    assertThat(listing(slow).path("marginPct").isNull()).isTrue();
    Map<String, JsonNode> alerts = new java.util.HashMap<>();
    Integer offset = 0;
    while (offset != null) {
      JsonNode page =
          get("/internal/merchant/inventory-alerts?limit=50&offset=" + offset).getBody();
      for (JsonNode alert : page.path("items")) {
        if (alert.path("listingId").asText().startsWith(prefix)) {
          alerts.put(alert.path("listingId").asText(), alert);
        }
      }
      offset = page.path("nextOffset").isNull() ? null : page.path("nextOffset").intValue();
    }
    assertThat(alerts.keySet()).containsExactlyInAnyOrder(low, slow);
    assertThat(alerts.get(low).path("kind").asText()).isEqualTo("low_stock");
    assertThat(alerts.get(low).path("daysOfCover").decimalValue()).isEqualByComparingTo("60");
    assertThat(alerts.get(slow).path("kind").asText()).isEqualTo("slow_mover");
    assertThat(alerts.get(slow).path("daysOfCover").isNull()).isTrue();
    assertThat(alerts.get(slow).path("storefrontVisible").asBoolean()).isFalse();
    fixture.update("UPDATE product SET stock_quantity=7,price_minor=1200 WHERE product_id=?", low);
    assertThat(listing(low).path("stockQuantity").asLong()).isEqualTo(7);
    assertThat(listing(low).path("priceMinor").asLong()).isEqualTo(1200);
    assertThat(listing(low).path("salesLast30d").path("units").asLong()).isEqualTo(2);
  }

  @Test
  void paidAndRefundCountsUseHistoricalPaymentTruthAndShanghaiHalfOpenCohort() throws Exception {
    String sku = product("paid", 9000, 10, true, "PUBLISHED");
    Paid first = paid(sku, 2, START, "SUCCEEDED", true, prefix);
    Paid second = paid(sku, 1, END.minusNanos(1000), "SUCCEEDED", true, prefix);
    paid(sku, 3, START.minusNanos(1000), "SUCCEEDED", true, prefix);
    paid(sku, 4, END, "SUCCEEDED", true, prefix);
    paid(sku, 5, START.plusSeconds(1), "PENDING", true, prefix);
    Paid wrongAmount = paid(sku, 6, START.plusSeconds(1), "SUCCEEDED", true, prefix);
    fixture.update(
        "UPDATE mock_payment_attempt SET amount_minor=6001 WHERE attempt_id=?",
        wrongAmount.attemptId());
    paid(sku, 7, START.plusSeconds(1), "SUCCEEDED", true, prefix + "-other");
    paid(sku, 8, START.plusSeconds(1), "SUCCEEDED", false, prefix);
    refund(first, prefix, "CNY", START.plusSeconds(1));
    refund(first, prefix, "CNY", START.plusSeconds(2));
    refund(second, prefix, "CNY", END);
    refund(second, prefix + "-other", "CNY", END.minusNanos(1000));
    refund(second, prefix, "USD", END.minusNanos(1000));
    JsonNode detail = listing(sku);
    assertThat(detail.path("window").path("start").asText()).isEqualTo(START.toString());
    assertThat(detail.path("window").path("end").asText()).isEqualTo(END.toString());
    assertThat(detail.path("salesLast30d").path("orderCount").asLong()).isEqualTo(2);
    assertThat(detail.path("salesLast30d").path("units").asLong()).isEqualTo(3);
    assertThat(detail.path("salesLast30d").path("refundRequestedOrderCount").asLong()).isEqualTo(1);
    assertThat(detail.path("salesLast30d").path("refundRequestedOrderPct").decimalValue())
        .isEqualByComparingTo("50");
    assertThat(detail.path("priceMinor").asLong()).isEqualTo(9000);
    assertThat(
            fixture.queryForObject(
                "SELECT unit_price_minor FROM standard_order WHERE order_id=?",
                Long.class,
                first.orderId()))
        .isEqualTo(1000);
  }

  @Test
  void actualHttpBoundaryRejectsScopeActorDuplicatesAndUnknownParameters() throws Exception {
    String sku = product("auth", 1000, 4, true, "PUBLISHED");
    for (String path :
        List.of(
            "/internal/merchant/listings",
            "/internal/merchant/listings/" + sku,
            "/internal/merchant/inventory-alerts")) {
      assertThat(request(path, "shopping-agent", "merchant:read", null).getStatusCode())
          .isEqualTo(HttpStatus.FORBIDDEN);
      assertThat(request(path, "merchant-agent", "merchant:change:read", null).getStatusCode())
          .isEqualTo(HttpStatus.FORBIDDEN);
      assertThat(request(path, "merchant-agent", "merchant:read", "sandbox").getStatusCode())
          .isEqualTo(HttpStatus.FORBIDDEN);
      assertThat(get(path + "?owner=someone-else").getStatusCode())
          .isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(get(path + "?asOf=" + AS_OF + "&asOf=" + AS_OF).getStatusCode())
          .isEqualTo(HttpStatus.BAD_REQUEST);
    }
    assertThat(get("/internal/merchant/inventory-alerts?limit=51").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/internal/merchant/listings/missing-" + prefix).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/internal/merchant/listings/" + sku).getHeaders().getCacheControl())
        .isEqualTo("no-store");
  }

  private String product(String suffix, long price, long stock, boolean available, String state) {
    String id = prefix + "-" + suffix;
    fixture.update(
        "INSERT INTO product(product_id,name,description,price_minor,currency,stock_quantity,available,publication_state,publication_version) VALUES (?,?,'Listing fixture',?,'CNY',?,?,?,1)",
        id,
        id,
        price,
        stock,
        available,
        state);
    products.add(id);
    return id;
  }

  private String family(String small, String large) {
    String id = prefix + "-family";
    fixture.update(
        "INSERT INTO retail_product_family(family_id,name,description,content,options) VALUES (?,?,'Family description','{\"category\":\"home\",\"longDescription\":\"Family long content\"}','[{\"name\":\"size\",\"values\":[\"M\",\"L\"]}]')",
        id,
        id);
    families.add(id);
    for (int index = 0; index < 2; index++) {
      fixture.update(
          "INSERT INTO retail_product_metadata(product_id,family_id,content,option_values,display_order) VALUES (?,?,'{}',CAST(? AS JSON),?)",
          index == 0 ? small : large,
          id,
          "{\"size\":\"" + (index == 0 ? "M" : "L") + "\"}",
          index);
    }
    return id;
  }

  private void facts(String sku, Long cost, long threshold, String quality) {
    fixture.update(
        "INSERT INTO retail_product_operations(product_id,unit_cost_minor,low_stock_threshold,content_quality,missing_attributes,facts_version,observed_at,source_ref) VALUES (?,?,?,?,CAST(? AS JSON),1,?,?)",
        sku,
        cost,
        threshold,
        quality,
        quality.equals("needs_work") ? "[\"material\"]" : "[]",
        Timestamp.from(AS_OF),
        prefix);
  }

  private Paid paid(
      String sku,
      int quantity,
      Instant paidAt,
      String state,
      boolean paidOrder,
      String paymentOwner) {
    String order = UUID.randomUUID().toString();
    String attempt = UUID.randomUUID().toString();
    fixture.update(
        "INSERT INTO standard_order(order_id,user_subject,product_id,product_name,unit_price_minor,currency,quantity,total_price_minor,product_version,status,state_version,created_at) VALUES (?,?,?,'Historical price',1000,'CNY',?,?,1,?,?,?)",
        order,
        prefix,
        sku,
        quantity,
        1000L * quantity,
        paidOrder ? "PAID" : "UNPAID",
        paidOrder ? 2 : 1,
        Timestamp.from(paidAt.minusSeconds(1)));
    fixture.update(
        "INSERT INTO mock_payment_attempt(attempt_id,callback_correlation_id,user_subject,order_id,order_kind,request_idempotency_key,intent_hash,amount_minor,currency,state,state_version,succeeded_at) VALUES (?,?,?,?,'STANDARD',?, ?,?,'CNY',?,?,?)",
        attempt,
        UUID.randomUUID().toString(),
        paymentOwner,
        order,
        attempt,
        "a".repeat(64),
        1000L * quantity,
        state,
        state.equals("PENDING") ? 1 : 2,
        state.equals("SUCCEEDED") ? Timestamp.from(paidAt) : null);
    Paid paid = new Paid(order, attempt);
    orders.add(paid);
    return paid;
  }

  private void refund(Paid paid, String owner, String currency, Instant created) {
    String id = UUID.randomUUID().toString();
    fixture.update(
        "INSERT INTO mock_refund(refund_id,user_subject,order_id,order_kind,payment_attempt_id,request_idempotency_key,intent_hash,eligible_amount_minor,requested_amount_minor,currency,state,state_version,created_at) VALUES (?,?,?,'STANDARD',?,?,?,1000,100,?,'REQUESTED',1,?)",
        id,
        owner,
        paid.orderId(),
        paid.attemptId(),
        id,
        "b".repeat(64),
        currency,
        Timestamp.from(created));
    refunds.add(id);
  }

  private JsonNode listing(String id) throws Exception {
    ResponseEntity<JsonNode> response = get("/internal/merchant/listings/" + id);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody();
  }

  private ResponseEntity<JsonNode> get(String path) throws Exception {
    return request(path, "merchant-agent", "merchant:read", null);
  }

  private ResponseEntity<JsonNode> request(String path, String actor, String scope, String sandbox)
      throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience("commerce-service")
            .subject(prefix)
            .claim("user_id", prefix)
            .claim("token_type", "agent_obo")
            .claim("scope", scope)
            .claim("session", prefix)
            .claim("act", Map.of("azp", actor))
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(120)));
    if (sandbox != null) {
      claims.claim("sandbox", sandbox);
    }
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("catalog-current").build(),
            claims.build());
    jwt.sign(new RSASSASigner(key));
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwt.serialize());
    headers.set("X-Merchant-Session-Id", prefix);
    return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }

  private record Paid(String orderId, String attemptId) {}
}
