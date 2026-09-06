package io.citybuddy.commerce.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(
    classes = MerchantOrderIssueIntegrationTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MerchantOrderIssueIntegrationTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({
    MerchantOrderIssueConfiguration.class,
    MerchantOrderIssueController.class,
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
  @Autowired private MerchantOrderIssueService service;
  private JdbcTemplate fixture;
  private String owner;
  private String sku;
  private String session;
  private RSAPrivateKey key;

  @BeforeEach
  void setup() throws Exception {
    owner = "issue-it-" + UUID.randomUUID().toString().substring(0, 8);
    sku = owner + "-sku";
    session = "merchant-" + UUID.randomUUID();
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
  void removeOwnIssues() {
    fixture.update("DELETE FROM retail_order_issue WHERE source_ref = BINARY ?", owner);
    fixture.update("DELETE FROM retail_order_fulfillment WHERE source_ref = BINARY ?", owner);
  }

  @Test
  void merchantReadsSameDelayFactAndCannotReplaceIdentityWithBuyerOrEvaluationContext()
      throws Exception {
    String order = order();
    payment(order, "SUCCEEDED", owner);
    issue("late", order, "delayed", false);
    fixture.update(
        """
        INSERT INTO retail_order_fulfillment
          (order_id,method,stage,estimated_delivery_at,delay_reason,source_kind,source_ref,observed_at)
        VALUES (?,'STANDARD','PROCESSING','2026-09-09 12:00:00','Warehouse preparation delay',
          'FIXTURE',?,'2026-09-05 12:00:00')
        """,
        order,
        owner);
    issue("closed", order, "buyer_message", true);
    ResponseEntity<JsonNode> result = get("merchant-agent", "merchant:read", null, "20");
    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(result.getHeaders().getCacheControl()).contains("no-store");
    JsonNode found = ownIssue(result.getBody(), "late");
    assertThat(found.path("orderId").asText()).isEqualTo(order);
    assertThat(found.path("listingId").asText()).isEqualTo(sku);
    assertThat(found.path("fulfillment").path("stage").asText()).isEqualTo("PROCESSING");
    assertThat(found.path("fulfillment").path("shippedAt").isNull()).isTrue();
    assertThat(found.path("fulfillment").path("delayReason").asText())
        .isEqualTo("Warehouse preparation delay");
    assertThat(result.getBody().findValuesAsText("issueId")).doesNotContain(owner + "-closed");
    assertThat(get("shopping-agent", "merchant:read", null, "20").getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("merchant-agent", "merchant:price:prepare", null, "20").getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("merchant-agent", "merchant:read", "sandbox", "20").getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("merchant-agent", "merchant:read", null, "101").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            jdbc.queryForObject(
                "SELECT state_version FROM standard_order WHERE order_id=?", Long.class, order))
        .isEqualTo(2);
  }

  @Test
  void refundSpikeCountsDistinctPaidOrdersAndExcludesWrongOwnerKindFailureAndWindow()
      throws Exception {
    String first = order();
    String second = order();
    String failed = order();
    String pending = order();
    String firstPayment = payment(first, "SUCCEEDED", owner);
    String secondPayment = payment(second, "SUCCEEDED", owner);
    String failedPayment = payment(failed, "SUCCEEDED", owner);
    String pendingPayment = payment(pending, "PENDING", owner);
    refund(firstPayment, first, owner, "STANDARD", "REQUESTED", "2026-09-03T12:00:00Z");
    refund(firstPayment, first, owner, "STANDARD", "REQUESTED", "2026-09-03T13:00:00Z");
    refund(secondPayment, second, owner, "STANDARD", "PROCESSING", "2026-09-04T12:00:00Z");
    refund(failedPayment, failed, owner, "STANDARD", "FAILED", "2026-09-03T12:00:00Z");
    refund(failedPayment, failed, "wrong-owner", "STANDARD", "REQUESTED", "2026-09-03T12:00:00Z");
    refund(failedPayment, failed, owner, "SECKILL", "REQUESTED", "2026-09-03T12:00:00Z");
    refund(failedPayment, failed, owner, "STANDARD", "REQUESTED", "2026-09-06T00:00:00Z");
    refund(pendingPayment, pending, owner, "STANDARD", "REQUESTED", "2026-09-03T12:00:00Z");
    issue("spike", first, "return_spike", false);
    JsonNode result =
        ownIssue(get("merchant-agent", "merchant:read", null, "100").getBody(), "spike");
    assertThat(result.path("refundRequestedOrderCount").asLong()).isEqualTo(2);
    assertThat(result.path("summary").asText()).startsWith("2 paid orders");
    assertThat(result.path("windowEnd").asText()).isEqualTo("2026-09-06T00:00:00Z");
    assertThat(result.path("fulfillment").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM mock_refund WHERE payment_attempt_id=?",
                Long.class,
                firstPayment))
        .isEqualTo(2);
  }

  @Test
  void delayedIssueWithoutSupportingFactsFailsInsteadOfInventingAShipment() throws Exception {
    String order = order();
    payment(order, "SUCCEEDED", owner);
    issue("unsupported", order, "delayed", false);
    assertThatThrownBy(() -> service.list(100))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("supporting fulfillment");
    assertThat(get("merchant-agent", "merchant:read", null, "100").getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  private String order() {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        """
        INSERT INTO standard_order (order_id,user_subject,product_id,product_name,unit_price_minor,
          currency,quantity,total_price_minor,product_version,status,state_version,created_at)
        VALUES (?,?,?,'Historical retail item',1000,'CNY',1,1000,1,'PAID',2,'2026-09-01 12:00:00')
        """,
        id,
        owner,
        sku);
    return id;
  }

  private String payment(String order, String state, String subject) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        """
        INSERT INTO mock_payment_attempt (attempt_id,callback_correlation_id,user_subject,
          order_id,order_kind,request_idempotency_key,intent_hash,amount_minor,currency,
          state,state_version,succeeded_at)
        VALUES (?,?,?,?,'STANDARD',?, ?,1000,'CNY',?,?,?)
        """,
        id,
        UUID.randomUUID().toString(),
        subject,
        order,
        id,
        "a".repeat(64),
        state,
        state.equals("PENDING") ? 1 : 2,
        state.equals("SUCCEEDED") ? Timestamp.from(Instant.parse("2026-09-01T12:00:01Z")) : null);
    return id;
  }

  private void refund(
      String attempt, String order, String subject, String kind, String state, String created) {
    String id = UUID.randomUUID().toString();
    boolean requested = state.equals("REQUESTED");
    boolean failed = state.equals("FAILED");
    Timestamp time = Timestamp.from(Instant.parse(created));
    jdbc.update(
        """
        INSERT INTO mock_refund (refund_id,user_subject,order_id,order_kind,payment_attempt_id,
          request_idempotency_key,intent_hash,eligible_amount_minor,requested_amount_minor,
          currency,state,state_version,failure_code,processing_at,completed_at,created_at)
        VALUES (?,?,?,?,?,?,?,1000,100,'CNY',?,?,?,?,?,?)
        """,
        id,
        subject,
        order,
        kind,
        attempt,
        id,
        "b".repeat(64),
        state,
        requested ? 1 : failed ? 3 : 2,
        failed ? "CONTROLLED_FAILURE" : null,
        requested ? null : time,
        failed ? time : null,
        time);
  }

  private void issue(String suffix, String order, String kind, boolean resolved) {
    fixture.update(
        """
        INSERT INTO retail_order_issue (issue_id,order_id,kind,summary,opened_at,resolved_at,
          window_start,window_end,source_kind,source_ref)
        VALUES (?,?,?,'Fixture issue; counts must be derived','2026-09-05 12:00:00',?,?,?,'FIXTURE',?)
        """,
        owner + "-" + suffix,
        order,
        kind,
        resolved ? Timestamp.from(Instant.parse("2026-09-06T12:00:00Z")) : null,
        kind.equals("return_spike") ? Timestamp.from(Instant.parse("2026-09-02T00:00:00Z")) : null,
        kind.equals("return_spike") ? Timestamp.from(Instant.parse("2026-09-06T00:00:00Z")) : null,
        owner);
  }

  private JsonNode ownIssue(JsonNode response, String suffix) {
    for (JsonNode issue : response) {
      if (issue.path("issueId").asText().equals(owner + "-" + suffix)) {
        return issue;
      }
    }
    throw new AssertionError("Expected fixture issue missing");
  }

  private ResponseEntity<JsonNode> get(String actor, String scope, String sandbox, String limit)
      throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience("commerce-service")
            .subject(owner)
            .claim("user_id", owner)
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
    jwt.sign(new RSASSASigner(key));
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwt.serialize());
    headers.set("X-Merchant-Session-Id", session);
    if (sandbox != null) {
      headers.set("X-Eval-Sandbox-Id", sandbox);
    }
    return http.exchange(
        "/internal/merchant/order-issues?limit=" + limit,
        HttpMethod.GET,
        new HttpEntity<>(headers),
        JsonNode.class);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }
}
