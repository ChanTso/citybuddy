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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
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

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(
    classes = MerchantOrderIntegrationTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MerchantOrderIntegrationTest {
  // A bounded global page must not encounter intentionally invalid rows left by other suites.
  private static final Instant CREATED = Instant.parse("2037-12-01T12:00:00Z");

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({
    MerchantOrderConfiguration.class,
    MerchantOrderController.class,
    MerchantExceptionHandler.class,
    OboIdentityConfiguration.class
  })
  static class Application {}

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("citybuddy.merchant.enabled", () -> "true");
    registry.add("citybuddy.obo.enabled", () -> "true");
    registry.add("citybuddy.obo.issuer", () -> "https://identity.citybuddy.test");
    registry.add("citybuddy.obo.jwks-url", () -> required("IDENTITY_JWKS_URL"));
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TestRestTemplate http;
  private final List<String> standardOrders = new ArrayList<>();
  private final List<String> seckillOrders = new ArrayList<>();
  private final List<String> attempts = new ArrayList<>();
  private final List<String> refunds = new ArrayList<>();
  private final List<String> sandboxes = new ArrayList<>();
  private JdbcTemplate fixture;
  private String owner;
  private String secondBuyer;
  private String sku;
  private String session;
  private RSAPrivateKey signingKey;

  @BeforeEach
  void setup() throws Exception {
    fixture =
        new JdbcTemplate(
            new DriverManagerDataSource(
                required("CATALOG_MYSQL_URL"), "root", required("MYSQL_BOOTSTRAP_PASSWORD")));
    owner = "merchant-orders-it-" + UUID.randomUUID().toString().substring(0, 8);
    secondBuyer = owner + "-second";
    sku = owner + "-sku";
    session = "merchant-" + UUID.randomUUID();
    String encoded =
        Files.readString(Path.of(required("CATALOG_TEST_SIGNING_PRIVATE_KEY_PATH")))
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    signingKey =
        (RSAPrivateKey)
            KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    fixture.update(
        """
        INSERT INTO product (product_id,name,description,price_minor,currency,stock_quantity,
          available,publication_state,publication_version)
        VALUES (?,'Current catalog name','Current description',9999,'CNY',10,TRUE,'PUBLISHED',12)
        """,
        sku);
  }

  @AfterEach
  void removeOnlyThisTestsFacts() {
    for (String id : standardOrders) {
      fixture.update("DELETE FROM retail_order_fulfillment WHERE order_id=?", id);
    }
    for (String id : refunds) {
      fixture.update("DELETE FROM mock_refund WHERE refund_id=?", id);
    }
    for (String id : attempts) {
      fixture.update("DELETE FROM mock_payment_attempt WHERE attempt_id=?", id);
    }
    for (String id : standardOrders) {
      fixture.update("DELETE FROM standard_order WHERE order_id=?", id);
    }
    for (String id : seckillOrders) {
      fixture.update("DELETE FROM seckill_order WHERE order_id=?", id);
    }
    for (String id : sandboxes) {
      fixture.update("DELETE FROM eval_sandbox WHERE sandbox_id=?", id);
    }
    fixture.update("DELETE FROM product WHERE product_id=?", sku);
  }

  @Test
  void recentPageUsesOrderCreationAcrossBuyersAndKindsWithHistoricalBusinessFacts()
      throws Exception {
    String oldPaid = standard(owner, "PAID", CREATED, null);
    String newerPaid = standard(secondBuyer, "PAID", CREATED.plusSeconds(1), null);
    String pending = seckill(owner, "UNPAID", CREATED.plusSeconds(4));
    String failed = standard(secondBuyer, "UNPAID", CREATED.plusSeconds(4), null);
    String cancelled = seckill(secondBuyer, "CANCELLED", CREATED.plusSeconds(5));
    String noAttempt = standard(owner, "UNPAID", CREATED.plusSeconds(6), null);
    String paidAttempt =
        payment(oldPaid, "STANDARD", owner, "SUCCEEDED", null, CREATED.plusSeconds(80));
    payment(newerPaid, "STANDARD", secondBuyer, "SUCCEEDED", null, CREATED.plusSeconds(10));
    payment(pending, "SECKILL", owner, "PENDING", null, null);
    payment(failed, "STANDARD", secondBuyer, "FAILED", null, null);
    payment(cancelled, "SECKILL", secondBuyer, "FAILED", null, null);
    refund(paidAttempt, oldPaid, "REQUESTED", 300);
    refund(paidAttempt, oldPaid, "PROCESSING", 200);
    refund(paidAttempt, oldPaid, "FAILED", 400);
    fulfillment(oldPaid);

    ResponseEntity<JsonNode> response = get("", "merchant-agent", "merchant:read", session, null);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    JsonNode page = response.getBody();
    List<String> tied =
        List.of(pending, failed).stream().sorted(Comparator.reverseOrder()).toList();
    assertThat(page.findValuesAsText("orderId"))
        .containsExactly(noAttempt, cancelled, tied.get(0), tied.get(1), newerPaid, oldPaid);
    assertThat(page.get(0).path("status").asText()).isEqualTo("UNPAID");
    assertThat(page.get(0).path("payment").isNull()).isTrue();
    assertThat(find(page, pending).path("payment").path("state").asText()).isEqualTo("PENDING");
    assertThat(find(page, failed).path("payment").path("state").asText()).isEqualTo("FAILED");
    assertThat(find(page, cancelled).path("status").asText()).isEqualTo("CANCELLED");
    assertThat(find(page, cancelled).path("payment").path("state").asText()).isEqualTo("FAILED");
    assertThat(find(page, pending).path("product").path("productVersion").isNull()).isTrue();
    JsonNode old = find(page, oldPaid);
    assertThat(old.path("status").asText()).isEqualTo("PAID");
    assertThat(old.path("createdAt").asText()).isEqualTo(CREATED.toString());
    assertThat(old.path("payment").path("succeededAt").asText())
        .isEqualTo(CREATED.plusSeconds(80).toString());
    assertThat(old.path("product").path("name").asText()).isEqualTo("Historical product");
    assertThat(old.path("product").path("unitPriceMinor").asLong()).isEqualTo(1250);
    assertThat(old.path("product").path("totalPriceMinor").asLong()).isEqualTo(2500);
    assertThat(old.path("product").path("productVersion").asLong()).isEqualTo(7);
    assertThat(old.path("payment").path("refundedAmountMinor").asLong()).isZero();
    assertThat(old.path("refunds").path("reservedAmountMinor").asLong()).isEqualTo(500);
    JsonNode requested = refundState(old, "REQUESTED");
    assertThat(requested.path("requestedAmountMinor").asLong()).isEqualTo(300);
    assertThat(requested.path("refundedAmountMinor").asLong()).isZero();
    assertThat(old.path("fulfillment").path("stage").asText()).isEqualTo("DELIVERED");
    assertThat(old.path("fulfillment").path("deliveredAt").asText())
        .isEqualTo(CREATED.plusSeconds(300).toString());
    assertThat(old.path("fulfillment").path("estimatedDeliveryAt").asText())
        .isEqualTo(CREATED.plusSeconds(500).toString());
    assertThat(old.path("fulfillment").path("sourceRef").asText()).isEqualTo(owner);
    assertThat(find(page, noAttempt).path("fulfillment").isNull()).isTrue();
    JsonNode limited = get("?limit=3", "merchant-agent", "merchant:read", session, null).getBody();
    assertThat(limited.size()).isEqualTo(3);
    for (int index = 0; index < limited.size(); index++) {
      assertThat(limited.get(index)).isEqualTo(page.get(index));
    }
    assertThat(get("?limit=3", "merchant-agent", "merchant:read", session, null).getBody())
        .isEqualTo(limited);
    assertThat(
            jdbc.queryForObject(
                "SELECT price_minor FROM product WHERE product_id=?", Long.class, sku))
        .isEqualTo(9999);
    assertThat(
            jdbc.queryForObject(
                "SELECT refunded_amount_minor FROM mock_payment_attempt WHERE attempt_id=?",
                Long.class,
                paidAttempt))
        .isZero();
  }

  @Test
  void excludesSandboxAndPollutedPaymentsWithoutLettingBuyerTokensReadTheStore() throws Exception {
    String sandbox = sandbox();
    String evaluated = standard(owner, "PAID", CREATED.plusSeconds(100), sandbox);
    payment(evaluated, "STANDARD", owner, "SUCCEEDED", sandbox, CREATED.plusSeconds(101));
    String sandboxPayment = standard(owner, "UNPAID", CREATED, null);
    payment(sandboxPayment, "STANDARD", owner, "SUCCEEDED", sandbox, CREATED.plusSeconds(1));
    String wrongOwner = standard(owner, "UNPAID", CREATED.plusSeconds(2), null);
    payment(wrongOwner, "STANDARD", secondBuyer, "SUCCEEDED", null, CREATED.plusSeconds(3));
    String wrongKind = standard(secondBuyer, "UNPAID", CREATED.plusSeconds(4), null);
    payment(wrongKind, "SECKILL", secondBuyer, "SUCCEEDED", null, CREATED.plusSeconds(5));
    ResponseEntity<JsonNode> response =
        get("?limit=3", "merchant-agent", "merchant:read", session, null);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().findValuesAsText("orderId"))
        .containsExactly(wrongKind, wrongOwner, sandboxPayment);
    response.getBody().forEach(order -> assertThat(order.path("payment").isNull()).isTrue());
    assertThat(get("?limit=3", "shopping-agent", "merchant:read", session, null).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            get("?limit=3", "merchant-agent", "merchant:price:read", session, null).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            get("?limit=3", "merchant-agent", "merchant:read", "other-session", null)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("?limit=3", "merchant-agent", "merchant:read", session, sandbox).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM standard_order WHERE product_id=? AND sandbox_id IS NULL",
                Long.class,
                sku))
        .isEqualTo(3);
  }

  @Test
  void paidOrderRequiresAnActualMatchingSuccessfulPayment() throws Exception {
    String order = standard(owner, "PAID", CREATED, null);
    String attempt =
        payment(order, "STANDARD", secondBuyer, "SUCCEEDED", null, CREATED.plusSeconds(1));
    assertThat(get("?limit=1", "merchant-agent", "merchant:read", session, null).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    fixture.update(
        "UPDATE mock_payment_attempt SET user_subject=?,order_kind='SECKILL' WHERE attempt_id=?",
        owner,
        attempt);
    assertThat(get("?limit=1", "merchant-agent", "merchant:read", session, null).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    fixture.update(
        """
        UPDATE mock_payment_attempt SET order_kind='STANDARD',state='PENDING',state_version=1,
          succeeded_at=NULL WHERE attempt_id=?
        """,
        attempt);
    assertThat(get("?limit=1", "merchant-agent", "merchant:read", session, null).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    fixture.update(
        "UPDATE mock_payment_attempt SET state='SUCCEEDED',state_version=2,succeeded_at=? WHERE attempt_id=?",
        Timestamp.from(CREATED.plusSeconds(1)),
        attempt);
    ResponseEntity<JsonNode> response =
        get("?limit=1", "merchant-agent", "merchant:read", session, null);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().get(0).path("orderId").asText()).isEqualTo(order);
    assertThat(response.getBody().get(0).path("payment").path("attemptId").asText())
        .isEqualTo(attempt);
  }

  private String standard(String subject, String status, Instant created, String sandbox) {
    String id = UUID.randomUUID().toString();
    standardOrders.add(id);
    jdbc.update(
        """
        INSERT INTO standard_order (order_id,user_subject,sandbox_id,evaluation_owner_handle,
          product_id,product_name,unit_price_minor,currency,quantity,total_price_minor,
          product_version,status,state_version,created_at)
        VALUES (?,?,?,?,?,'Historical product',1250,'CNY',2,2500,7,?,?,?)
        """,
        id,
        subject,
        sandbox,
        sandbox == null ? null : "h".repeat(43),
        sku,
        status,
        status.equals("PAID") ? 2 : 1,
        Timestamp.from(created));
    return id;
  }

  private String seckill(String subject, String status, Instant created) {
    String id = UUID.randomUUID().toString();
    seckillOrders.add(id);
    jdbc.update(
        """
        INSERT INTO seckill_order (order_id,reservation_id,transaction_event_id,timeout_event_id,
          user_subject,activity_id,product_id,product_name,unit_price_minor,currency,quantity,
          total_price_minor,status,state_version,cancellation_projection_version,unpaid_deadline,created_at)
        VALUES (?,?,?,?,?,?,?,'Historical seckill',1250,'CNY',2,2500,?,?,?,?,?)
        """,
        id,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        subject,
        "activity-" + id,
        sku,
        status,
        status.equals("UNPAID") ? 1 : 2,
        status.equals("CANCELLED") ? 4L : null,
        Timestamp.from(created.plusSeconds(900)),
        Timestamp.from(created));
    return id;
  }

  private String payment(
      String order, String kind, String subject, String state, String sandbox, Instant succeeded) {
    String id = UUID.randomUUID().toString();
    attempts.add(id);
    jdbc.update(
        """
        INSERT INTO mock_payment_attempt (attempt_id,callback_correlation_id,user_subject,
          order_id,order_kind,sandbox_id,request_idempotency_key,intent_hash,amount_minor,
          refunded_amount_minor,currency,state,state_version,succeeded_at)
        VALUES (?,?,?,?,?,?,?,?,2500,0,'CNY',?,?,?)
        """,
        id,
        UUID.randomUUID().toString(),
        subject,
        order,
        kind,
        sandbox,
        id,
        "a".repeat(64),
        state,
        state.equals("PENDING") ? 1 : 2,
        succeeded == null ? null : Timestamp.from(succeeded));
    return id;
  }

  private void refund(String attempt, String order, String state, long amount) {
    String id = UUID.randomUUID().toString();
    refunds.add(id);
    boolean requested = state.equals("REQUESTED");
    boolean failed = state.equals("FAILED");
    jdbc.update(
        """
        INSERT INTO mock_refund (refund_id,user_subject,order_id,order_kind,payment_attempt_id,
          request_idempotency_key,intent_hash,eligible_amount_minor,requested_amount_minor,
          refunded_amount_minor,currency,state,state_version,failure_code,processing_at,completed_at)
        VALUES (?,?,?,'STANDARD',?,?,?,2500,?,0,'CNY',?,?,?,?,?)
        """,
        id,
        owner,
        order,
        attempt,
        id,
        "b".repeat(64),
        amount,
        state,
        requested ? 1 : failed ? 3 : 2,
        failed ? "CONTROLLED_FAILURE" : null,
        requested ? null : Timestamp.from(CREATED.plusSeconds(90)),
        failed ? Timestamp.from(CREATED.plusSeconds(91)) : null);
  }

  private void fulfillment(String order) {
    fixture.update(
        """
        INSERT INTO retail_order_fulfillment (order_id,method,stage,estimated_delivery_at,
          shipped_at,delivered_at,source_kind,source_ref,observed_at)
        VALUES (?,'STANDARD','DELIVERED',?,?,?,'FIXTURE',?,?)
        """,
        order,
        Timestamp.from(CREATED.plusSeconds(500)),
        Timestamp.from(CREATED.plusSeconds(200)),
        Timestamp.from(CREATED.plusSeconds(300)),
        owner,
        Timestamp.from(CREATED.plusSeconds(400)));
  }

  private String sandbox() {
    String id = "merchant-order-sandbox-" + UUID.randomUUID().toString().substring(0, 8);
    sandboxes.add(id);
    jdbc.update(
        """
        INSERT INTO eval_sandbox (sandbox_id,case_correlation,reset_idempotency_key,fixture_digest,
          fixture_count,test_user_label,requested_ttl_seconds,auth_provision_idempotency_key,
          auth_revoke_idempotency_key,lifecycle_state,auth_invalidation_state,
          provisioning_due_at,auth_expiry_upper_bound)
        VALUES (?,?,?,?,1,?,60,?,?,'PROVISIONING','UNPROVISIONED',
          TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),TIMESTAMPADD(SECOND,120,CURRENT_TIMESTAMP(6)))
        """,
        id,
        id,
        id,
        "c".repeat(64),
        owner,
        id + "-provision",
        id + "-revoke");
    return id;
  }

  private ResponseEntity<JsonNode> get(
      String query, String actor, String scope, String headerSession, String sandbox)
      throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience("commerce-service")
            .subject(owner + "-operator")
            .claim("user_id", owner + "-operator")
            .claim("session", session)
            .claim("scope", scope)
            .claim("token_type", "agent_obo")
            .claim("act", Map.of("azp", actor))
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString())
            .build();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("catalog-current").build(), claims);
    jwt.sign(new RSASSASigner(signingKey));
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwt.serialize());
    headers.set("X-Merchant-Session-Id", headerSession);
    if (sandbox != null) {
      headers.set("X-Eval-Sandbox-Id", sandbox);
    }
    return http.exchange(
        "/internal/merchant/orders" + query,
        HttpMethod.GET,
        new HttpEntity<>(headers),
        JsonNode.class);
  }

  private static JsonNode find(JsonNode page, String orderId) {
    for (JsonNode order : page) {
      if (order.path("orderId").asText().equals(orderId)) {
        return order;
      }
    }
    throw new AssertionError("Expected order missing: " + orderId);
  }

  private static JsonNode refundState(JsonNode order, String state) {
    for (JsonNode item : order.path("refunds").path("byState")) {
      if (item.path("state").asText().equals(state)) {
        return item;
      }
    }
    throw new AssertionError("Expected refund state missing: " + state);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }
}
