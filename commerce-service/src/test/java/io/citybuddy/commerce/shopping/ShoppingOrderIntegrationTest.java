package io.citybuddy.commerce.shopping;

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
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(
    classes = ShoppingOrderIntegrationTest.Application.class,
    webEnvironment = WebEnvironment.RANDOM_PORT)
class ShoppingOrderIntegrationTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({
    ShoppingOrderConfiguration.class,
    ShoppingOrderController.class,
    ShoppingOrderExceptionHandler.class,
    OboIdentityConfiguration.class
  })
  static class Application {}

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("citybuddy.orders.enabled", () -> "true");
    registry.add("citybuddy.obo.enabled", () -> "true");
    registry.add("citybuddy.obo.issuer", () -> "https://identity.citybuddy.test");
    registry.add("citybuddy.obo.jwks-url", () -> required("IDENTITY_JWKS_URL"));
  }

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  private String owner;
  private String session;
  private RSAPrivateKey signingKey;

  @BeforeEach
  void identity() throws Exception {
    owner = "shopping-it-" + UUID.randomUUID().toString().substring(0, 8);
    session = "shop-" + UUID.randomUUID();
    String pem = Files.readString(Path.of(required("CATALOG_TEST_SIGNING_PRIVATE_KEY_PATH")));
    String encoded =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    signingKey =
        (RSAPrivateKey)
            KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
  }

  @Test
  void listsBothOrderKindsWithHistoricalPricesAndSeparateRefundStates() throws Exception {
    Instant created = Instant.parse("2026-09-01T12:00:00Z");
    String paid = standard(owner, "PAID", created, null);
    String pending = seckill(owner, "UNPAID", created.plusSeconds(1));
    String cancelled = seckill(owner, "CANCELLED", created.plusSeconds(2));
    String noAttempt = standard(owner, "UNPAID", created.plusSeconds(3), null);
    String attempt = payment(paid, "STANDARD", owner, "SUCCEEDED", null, 500);
    payment(pending, "SECKILL", owner, "PENDING", null, 0);
    payment(cancelled, "SECKILL", owner, "FAILED", null, 0);
    refund(attempt, paid, "STANDARD", owner, "REQUESTED", 300);
    refund(attempt, paid, "STANDARD", owner, "PROCESSING", 200);
    refund(attempt, paid, "STANDARD", owner, "SUCCEEDED", 500);
    refund(attempt, paid, "STANDARD", owner, "FAILED", 400);
    refund(attempt, paid, "STANDARD", "other-" + owner, "REQUESTED", 600);
    refund(attempt, UUID.randomUUID().toString(), "STANDARD", owner, "REQUESTED", 600);
    refund(attempt, paid, "SECKILL", owner, "REQUESTED", 600);

    ResponseEntity<JsonNode> result = get("/internal/shopping/orders", owner, null);
    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(result.getHeaders().getCacheControl()).contains("no-store");
    JsonNode orders = result.getBody();
    assertThat(orders.size()).isEqualTo(4);
    assertThat(orders.get(0).path("orderId").asText()).isEqualTo(noAttempt);
    assertThat(orders.get(0).path("payment").isNull()).isTrue();
    assertThat(orders.get(1).path("status").asText()).isEqualTo("CANCELLED");
    assertThat(orders.get(1).path("payment").path("state").asText()).isEqualTo("FAILED");
    assertThat(orders.get(2).path("orderKind").asText()).isEqualTo("SECKILL");
    assertThat(orders.get(2).path("product").path("productVersion").isNull()).isTrue();
    assertThat(orders.get(2).path("payment").path("state").asText()).isEqualTo("PENDING");
    JsonNode old = orders.get(3);
    assertThat(old.path("product").path("name").asText()).isEqualTo("Historical product");
    assertThat(old.path("product").path("unitPriceMinor").asLong()).isEqualTo(1250);
    assertThat(old.path("product").path("productVersion").asLong()).isEqualTo(7);
    assertThat(old.path("payment").path("refundedAmountMinor").asLong()).isEqualTo(500);
    assertThat(old.path("refunds").path("reservedAmountMinor").asLong()).isEqualTo(1000);
    Map<String, JsonNode> states = new HashMap<>();
    old.path("refunds")
        .path("byState")
        .forEach(state -> states.put(state.path("state").asText(), state));
    assertThat(states.keySet())
        .containsExactlyInAnyOrder("REQUESTED", "PROCESSING", "SUCCEEDED", "FAILED");
    assertThat(states.get("REQUESTED").path("count").asLong()).isEqualTo(1);
    assertThat(states.get("REQUESTED").path("refundedAmountMinor").asLong()).isZero();
    assertThat(states.get("SUCCEEDED").path("refundedAmountMinor").asLong()).isEqualTo(500);
    assertThat(old.path("fulfillment").isNull()).isTrue();
    assertThat(old.has("trackingUrl")).isFalse();
    assertThat(get("/internal/shopping/orders/" + paid, owner, null).getBody()).isEqualTo(old);
    JsonNode limited = get("/internal/shopping/orders?limit=2", owner, null).getBody();
    assertThat(limited.size()).isEqualTo(2);
    assertThat(limited.get(0)).isEqualTo(orders.get(0));
    assertThat(limited.get(1)).isEqualTo(orders.get(1));
  }

  @Test
  void productionReadsExcludeOtherOwnersSandboxOrdersAndPollutedPaymentJoins() throws Exception {
    Instant created = Instant.parse("2026-09-01T12:00:00Z");
    String own = standard(owner, "UNPAID", created, null);
    String other =
        standard(owner.toUpperCase(Locale.ROOT), "UNPAID", created.plusSeconds(10), null);
    String sandbox = sandbox();
    String evaluation = standard(owner, "PAID", created.plusSeconds(20), sandbox);
    payment(evaluation, "STANDARD", owner, "SUCCEEDED", sandbox, 0);
    payment(own, "STANDARD", owner, "SUCCEEDED", sandbox, 0);
    String wrongOwner = standard(owner, "UNPAID", created.plusSeconds(1), null);
    payment(wrongOwner, "STANDARD", "other-" + owner, "SUCCEEDED", null, 0);
    String wrongKind = standard(owner, "UNPAID", created.plusSeconds(2), null);
    payment(wrongKind, "SECKILL", owner, "SUCCEEDED", null, 0);

    JsonNode visible = get("/internal/shopping/orders", owner, null).getBody();
    assertThat(visible.size()).isEqualTo(3);
    visible.forEach(order -> assertThat(order.path("payment").isNull()).isTrue());
    for (String hidden : List.of(other, evaluation, UUID.randomUUID().toString())) {
      assertThat(get("/internal/shopping/orders/" + hidden, owner, null).getStatusCode())
          .isEqualTo(HttpStatus.NOT_FOUND);
    }
    assertThat(get("/internal/shopping/orders", owner, sandbox).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM standard_order WHERE BINARY user_subject=BINARY ? AND sandbox_id IS NULL",
                Long.class,
                owner))
        .isEqualTo(3);
  }

  @Test
  void conflictingPaidStateFailsInsteadOfReportingAnUnconfirmedSuccess() throws Exception {
    String order = standard(owner, "PAID", Instant.parse("2026-09-01T12:00:00Z"), null);
    payment(order, "STANDARD", owner, "PENDING", null, 0);
    assertThat(get("/internal/shopping/orders/" + order, owner, null).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Test
  void ownedFulfillmentUsesObservedStagesAndActualDeliveryInsteadOfEstimatedDates()
      throws Exception {
    Instant created = Instant.parse("2026-09-01T12:00:00Z");
    String shipped = standard(owner, "PAID", created, null);
    String delivered = standard(owner, "PAID", created.plusSeconds(1), null);
    String delayed = standard(owner, "PAID", created.plusSeconds(2), null);
    String noFacts = standard(owner, "UNPAID", created.plusSeconds(3), null);
    for (String id : List.of(shipped, delivered, delayed)) {
      String attempt = payment(id, "STANDARD", owner, "SUCCEEDED", null, 0);
      jdbc.update(
          "UPDATE mock_payment_attempt SET succeeded_at=? WHERE attempt_id=?",
          Timestamp.from(created.plusSeconds(10)),
          attempt);
    }
    fulfillment(shipped, "SHIPPED", null);
    fulfillment(delivered, "DELIVERED", null);
    fulfillment(delayed, "PROCESSING", "Warehouse preparation delay");
    JsonNode shipment =
        get("/internal/shopping/orders/" + shipped, owner, null).getBody().path("fulfillment");
    assertThat(shipment.path("stage").asText()).isEqualTo("SHIPPED");
    assertThat(shipment.path("shippedAt").asText()).isEqualTo("2026-09-02T12:00:00Z");
    assertThat(shipment.path("deliveredAt").isNull()).isTrue();
    JsonNode arrived =
        get("/internal/shopping/orders/" + delivered, owner, null).getBody().path("fulfillment");
    assertThat(arrived.path("deliveredAt").asText()).isEqualTo("2026-09-03T12:00:00Z");
    assertThat(arrived.path("estimatedDeliveryAt").asText()).isEqualTo("2026-09-05T12:00:00Z");
    JsonNode late =
        get("/internal/shopping/orders/" + delayed, owner, null).getBody().path("fulfillment");
    assertThat(late.path("delayReason").asText()).isEqualTo("Warehouse preparation delay");
    assertThat(late.path("shippedAt").isNull()).isTrue();
    assertThat(
            get("/internal/shopping/orders/" + noFacts, owner, null)
                .getBody()
                .path("fulfillment")
                .isNull())
        .isTrue();
    assertThat(
            get("/internal/shopping/orders/" + shipped, owner.toUpperCase(Locale.ROOT), null)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(state_version) FROM standard_order WHERE user_subject=?",
                Long.class,
                owner))
        .isEqualTo(7);
    assertThat(
            new ShoppingOrderRepository(jdbc)
                .findStandardOrders(owner, List.of(delivered))
                .getFirst()
                .fulfillment()
                .deliveredAt())
        .isEqualTo(Instant.parse("2026-09-03T12:00:00Z"));
  }

  @Test
  void fulfillmentCannotTurnAnUnpaidOrderIntoAnApparentShipment() throws Exception {
    String unpaid = standard(owner, "UNPAID", Instant.parse("2026-09-01T12:00:00Z"), null);
    fulfillment(unpaid, "SHIPPED", null);
    assertThat(get("/internal/shopping/orders/" + unpaid, owner, null).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  private void fulfillment(String order, String stage, String delayReason) {
    JdbcTemplate fixture =
        new JdbcTemplate(
            new DriverManagerDataSource(
                required("CATALOG_MYSQL_URL"), "root", required("MYSQL_BOOTSTRAP_PASSWORD")));
    fixture.update(
        """
        INSERT INTO retail_order_fulfillment
          (order_id,method,stage,promised_delivery_at,estimated_delivery_at,shipped_at,delivered_at,
           delay_reason,source_kind,source_ref,observed_at)
        VALUES (?,'STANDARD',?,'2026-09-04 12:00:00','2026-09-05 12:00:00',?,?,?,'FIXTURE',?,
          '2026-09-04 12:00:00')
        """,
        order,
        stage,
        stage.equals("PROCESSING") ? null : Timestamp.from(Instant.parse("2026-09-02T12:00:00Z")),
        stage.equals("DELIVERED") ? Timestamp.from(Instant.parse("2026-09-03T12:00:00Z")) : null,
        delayReason,
        owner);
  }

  private String standard(String subject, String status, Instant created, String sandbox) {
    String id = UUID.randomUUID().toString();
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
        "retired-" + id,
        status,
        status.equals("PAID") ? 2 : 1,
        Timestamp.from(created));
    return id;
  }

  private String seckill(String subject, String status, Instant created) {
    String id = UUID.randomUUID().toString();
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
        "retired-" + id,
        status,
        status.equals("UNPAID") ? 1 : 2,
        status.equals("CANCELLED") ? 4L : null,
        Timestamp.from(Instant.now().plusSeconds(3600)),
        Timestamp.from(created));
    return id;
  }

  private String payment(
      String order, String kind, String subject, String state, String sandbox, long refunded) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        """
        INSERT INTO mock_payment_attempt (attempt_id,callback_correlation_id,user_subject,
          order_id,order_kind,sandbox_id,request_idempotency_key,intent_hash,amount_minor,
          refunded_amount_minor,currency,state,state_version,succeeded_at)
        VALUES (?,?,?,?,?,?,? ,?,2500,?,'CNY',?,?,?)
        """,
        id,
        UUID.randomUUID().toString(),
        subject,
        order,
        kind,
        sandbox,
        id,
        "a".repeat(64),
        refunded,
        state,
        state.equals("PENDING") ? 1 : 2,
        state.equals("SUCCEEDED") ? Timestamp.from(Instant.now()) : null);
    return id;
  }

  private void refund(
      String attempt, String order, String kind, String subject, String state, long amount) {
    String id = UUID.randomUUID().toString();
    boolean requested = state.equals("REQUESTED");
    boolean terminal = state.equals("FAILED") || state.equals("SUCCEEDED");
    jdbc.update(
        """
        INSERT INTO mock_refund (refund_id,user_subject,order_id,order_kind,payment_attempt_id,
          request_idempotency_key,intent_hash,eligible_amount_minor,requested_amount_minor,
          refunded_amount_minor,currency,state,state_version,failure_code,processing_at,completed_at)
        VALUES (?,?,?,?,?,?,?,2500,?,?,'CNY',?,?,?,?,?)
        """,
        id,
        subject,
        order,
        kind,
        attempt,
        id,
        "b".repeat(64),
        amount,
        state.equals("SUCCEEDED") ? amount : 0,
        state,
        requested ? 1 : terminal ? 3 : 2,
        state.equals("FAILED") ? "CONTROLLED_FAILURE" : null,
        requested ? null : Timestamp.from(Instant.now()),
        terminal ? Timestamp.from(Instant.now()) : null);
  }

  private String sandbox() {
    String id = "shopping-sandbox-" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.update(
        """
        INSERT INTO eval_sandbox (sandbox_id,case_correlation,reset_idempotency_key,fixture_digest,
          fixture_count,test_user_label,requested_ttl_seconds,auth_provision_idempotency_key,
          auth_revoke_idempotency_key,lifecycle_state,auth_invalidation_state,
          provisioning_due_at,auth_expiry_upper_bound)
        VALUES (?,?,?, ?,1,?,60,?,?,'PROVISIONING','UNPROVISIONED',
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

  private ResponseEntity<JsonNode> get(String path, String subject, String sandbox)
      throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience("commerce-service")
            .subject(subject)
            .claim("user_id", subject)
            .claim("session", session)
            .claim("scope", "shopping:orders:read")
            .claim("token_type", "agent_obo")
            .claim("act", Map.of("azp", "shopping-agent"))
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString());
    if (sandbox != null) {
      claims.claim("sandbox", sandbox);
    }
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("catalog-current").build(),
            claims.build());
    jwt.sign(new RSASSASigner(signingKey));
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwt.serialize());
    headers.set("X-Shopping-Session-Id", session);
    if (sandbox != null) {
      headers.set("X-Eval-Sandbox-Id", sandbox);
    }
    return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }
}
