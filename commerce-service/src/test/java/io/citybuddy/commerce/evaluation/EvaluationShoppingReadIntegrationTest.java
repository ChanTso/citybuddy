package io.citybuddy.commerce.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import io.citybuddy.commerce.faq.FaqFixturePublisher;
import io.citybuddy.commerce.faq.FaqKnowledgeEventCodec;
import io.citybuddy.commerce.faq.FaqPublicationService;
import io.citybuddy.commerce.faq.FaqRepository;
import io.citybuddy.commerce.identity.JwksLoader;
import io.citybuddy.commerce.identity.OboIdentityConfiguration;
import io.citybuddy.commerce.shopping.ShoppingOrderRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
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
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("evaluation")
@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(
    classes = EvaluationShoppingReadIntegrationTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EvaluationShoppingReadIntegrationTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({
    EvaluationShoppingReadConfiguration.class,
    EvaluationShoppingReadController.class,
    EvaluationShoppingReadExceptionHandler.class,
    OboIdentityConfiguration.class
  })
  static class Application {
    @Bean
    DirectUserAuthorizer directUserAuthorizer(JwksLoader loader) {
      return new DirectUserAuthorizer(
          "https://identity.citybuddy.test",
          "citybuddy-web",
          Duration.ofMinutes(5),
          Duration.ZERO,
          "support:chat",
          loader,
          Clock.systemUTC(),
          true);
    }

    @Bean
    EvaluationSandboxAccess sandboxAccess(JdbcTemplate jdbc) {
      return new EvaluationSandboxAccess(new EvaluationSandboxRepository(jdbc), Clock.systemUTC());
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("citybuddy.catalog.enabled", () -> "false");
    registry.add("citybuddy.orders.enabled", () -> "false");
    registry.add("citybuddy.obo.enabled", () -> "true");
    registry.add("citybuddy.obo.issuer", () -> "https://identity.citybuddy.test");
    registry.add("citybuddy.obo.jwks-url", () -> required("IDENTITY_JWKS_URL"));
  }

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private PlatformTransactionManager transactionManager;
  private final List<String> orderIds = new ArrayList<>();
  private final List<String> seckillIds = new ArrayList<>();
  private final List<String> attemptIds = new ArrayList<>();
  private final List<String> refundIds = new ArrayList<>();
  private final List<String> sandboxIds = new ArrayList<>();
  private final List<String> faqIds = new ArrayList<>();
  private String owner;
  private String sandbox;
  private String session;
  private RSAPrivateKey signingKey;

  @BeforeEach
  void identity() throws Exception {
    owner = "eval-read-" + UUID.randomUUID();
    session = UUID.randomUUID().toString().replace("-", "") + "s".repeat(11);
    String pem = Files.readString(Path.of(required("CATALOG_TEST_SIGNING_PRIVATE_KEY_PATH")));
    String encoded =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    signingKey =
        (RSAPrivateKey)
            KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    sandbox = sandbox();
  }

  @AfterEach
  void removeOnlyOwnedFixtures() throws Exception {
    try (var connection =
        DriverManager.getConnection(
            required("CATALOG_MYSQL_URL"),
            "bootstrap_admin",
            required("MYSQL_BOOTSTRAP_PASSWORD"))) {
      var fixture = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      fixture.execute("SET ROLE 'bootstrap_grant_role'");
      try {
        for (String id : refundIds) {
          fixture.update("DELETE FROM mock_refund WHERE refund_id=?", id);
        }
        for (String id : attemptIds) {
          fixture.update("DELETE FROM mock_payment_attempt WHERE attempt_id=?", id);
        }
        for (String id : seckillIds) {
          fixture.update("DELETE FROM seckill_order WHERE order_id=?", id);
        }
        for (String id : orderIds) {
          fixture.update("DELETE FROM standard_order WHERE order_id=?", id);
        }
        for (String subject : List.of(owner, owner.toUpperCase(Locale.ROOT))) {
          fixture.update("DELETE FROM shopping_cart_command WHERE user_subject=BINARY ?", subject);
          fixture.update("DELETE FROM shopping_cart_item WHERE user_subject=BINARY ?", subject);
          fixture.update("DELETE FROM shopping_cart WHERE user_subject=BINARY ?", subject);
          fixture.update("DELETE FROM crm_profile WHERE user_subject=BINARY ?", subject);
        }
        for (String id : faqIds) {
          fixture.update(
              "DELETE FROM commerce_outbox WHERE aggregate_type='FAQ' AND aggregate_id=?", id);
          fixture.update("DELETE FROM faq_publication_command WHERE faq_id=?", id);
          fixture.update("DELETE FROM faq_draft_command WHERE faq_id=?", id);
          fixture.update("DELETE FROM faq_source WHERE faq_id=?", id);
        }
        for (String id : sandboxIds) {
          fixture.update("DELETE FROM eval_sandbox WHERE sandbox_id=?", id);
        }
      } finally {
        fixture.execute("SET ROLE NONE");
      }
    }
  }

  @Test
  void readsHistoricalPaymentAndRefundFactsWithCatalogAndOrdersDisabled() throws Exception {
    Instant created = Instant.parse("2026-09-01T12:00:00Z");
    String paid = standard(owner, "PAID", created, sandbox);
    String attempt = payment(paid, owner, "SUCCEEDED", sandbox, 500);
    refund(attempt, paid, "STANDARD", owner, "REQUESTED", 300);
    refund(attempt, paid, "STANDARD", owner, "PROCESSING", 200);
    refund(attempt, paid, "STANDARD", owner, "SUCCEEDED", 500);
    refund(attempt, paid, "STANDARD", owner, "FAILED", 400);
    refund(attempt, paid, "STANDARD", owner.toUpperCase(Locale.ROOT), "REQUESTED", 600);
    refund(attempt, UUID.randomUUID().toString(), "STANDARD", owner, "REQUESTED", 600);
    refund(attempt, paid, "SECKILL", owner, "REQUESTED", 600);

    ResponseEntity<JsonNode> result = orders("", owner, sandbox);
    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(result.getHeaders().getCacheControl()).contains("no-store");
    assertThat(result.getBody().size()).isEqualTo(1);
    JsonNode order = result.getBody().get(0);
    assertThat(order.path("orderKind").asText()).isEqualTo("STANDARD");
    assertThat(order.path("status").asText()).isEqualTo("PAID");
    assertThat(order.path("product").path("name").asText()).isEqualTo("Historical product");
    assertThat(order.path("product").path("unitPriceMinor").asLong()).isEqualTo(1250);
    assertThat(order.path("product").path("quantity").asInt()).isEqualTo(2);
    assertThat(order.path("product").path("totalPriceMinor").asLong()).isEqualTo(2500);
    assertThat(order.path("product").path("currency").asText()).isEqualTo("CNY");
    assertThat(order.path("product").path("productVersion").asLong()).isEqualTo(7);
    assertThat(order.path("payment").path("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(order.path("payment").path("refundedAmountMinor").asLong()).isEqualTo(500);
    assertThat(order.path("refunds").path("reservedAmountMinor").asLong()).isEqualTo(1000);
    Map<String, JsonNode> states = new HashMap<>();
    order
        .path("refunds")
        .path("byState")
        .forEach(state -> states.put(state.path("state").asText(), state));
    assertThat(states.keySet())
        .containsExactlyInAnyOrder("REQUESTED", "PROCESSING", "SUCCEEDED", "FAILED");
    assertThat(states.get("REQUESTED").path("count").asInt()).isEqualTo(1);
    assertThat(states.get("REQUESTED").path("refundedAmountMinor").asLong()).isZero();
    assertThat(states.get("SUCCEEDED").path("refundedAmountMinor").asLong()).isEqualTo(500);
    assertThat(order.path("fulfillment").isNull()).isTrue();
    assertThat(orders("/" + paid, owner, sandbox).getBody()).isEqualTo(order);
  }

  @Test
  void exactOwnerSandboxAndStandardOnlyReadsApplyLimitBeforeHydration() throws Exception {
    Instant created = Instant.parse("2026-09-01T12:00:00Z");
    List<String> own = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      own.add(standard(owner, "UNPAID", created, sandbox));
    }
    own.sort(Comparator.reverseOrder());
    String upper =
        standard(owner.toUpperCase(Locale.ROOT), "UNPAID", created.plusSeconds(10), sandbox);
    String otherSandbox = standard(owner, "UNPAID", created.plusSeconds(20), sandbox());
    String production = standard(owner, "UNPAID", created.plusSeconds(30), null);
    String seckill = seckill(created.plusSeconds(40));
    JsonNode limited = orders("?limit=2", owner, sandbox).getBody();
    assertThat(limited.size()).isEqualTo(2);
    assertThat(limited.get(0).path("orderId").asText()).isEqualTo(own.get(0));
    assertThat(limited.get(1).path("orderId").asText()).isEqualTo(own.get(1));
    assertThat(orders("", owner, sandbox).getBody().size()).isEqualTo(3);
    for (String hidden :
        List.of(upper, otherSandbox, production, seckill, UUID.randomUUID().toString())) {
      assertThat(orders("/" + hidden, owner, sandbox).getStatusCode())
          .isEqualTo(HttpStatus.NOT_FOUND);
    }
    assertThat(
            orders("/" + own.getFirst(), owner.toUpperCase(Locale.ROOT), sandbox).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    var repository = new ShoppingOrderRepository(jdbc);
    assertThat(repository.listForEvaluation(owner, sandbox.toUpperCase(Locale.ROOT), 20)).isEmpty();
    assertThat(
            repository.findForEvaluation(owner, sandbox.toUpperCase(Locale.ROOT), own.getFirst()))
        .isEmpty();
    assertThat(repository.list(owner, 20))
        .extracting(value -> value.orderId())
        .containsExactly(seckill, production);
  }

  @Test
  void excludesForeignPaymentJoinsAndFailsOnConflictingPaidTruth() throws Exception {
    Instant created = Instant.parse("2026-09-01T12:00:00Z");
    String wrongSandbox = standard(owner, "UNPAID", created, sandbox);
    payment(wrongSandbox, owner, "SUCCEEDED", sandbox(), 0);
    String wrongOwner = standard(owner, "UNPAID", created.plusSeconds(1), sandbox);
    payment(wrongOwner, owner.toUpperCase(Locale.ROOT), "SUCCEEDED", sandbox, 0);
    JsonNode clean = orders("", owner, sandbox).getBody();
    assertThat(clean.size()).isEqualTo(2);
    clean.forEach(order -> assertThat(order.path("payment").isNull()).isTrue());

    String paid = standard(owner, "PAID", created.plusSeconds(2), sandbox);
    String attempt = payment(paid, owner, "SUCCEEDED", sandbox, 0);
    jdbc.update("UPDATE mock_payment_attempt SET amount_minor=2600 WHERE attempt_id=?", attempt);
    assertThat(orders("/" + paid, owner, sandbox).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    jdbc.update(
        "UPDATE mock_payment_attempt SET amount_minor=2500,sandbox_id=? WHERE attempt_id=?",
        sandbox(),
        attempt);
    assertThat(orders("/" + paid, owner, sandbox).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Test
  void activeRegistryIsRequiredEvenWithValidSignedIdentity() throws Exception {
    standard(owner, "UNPAID", Instant.now(), sandbox);
    assertThat(orders("", owner, sandbox).getStatusCode()).isEqualTo(HttpStatus.OK);
    jdbc.update(
        "UPDATE eval_sandbox SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE sandbox_id=?",
        sandbox);
    assertThat(orders("", owner, sandbox).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(policies("?query=refund").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    jdbc.update(
        """
        UPDATE eval_sandbox SET expires_at=TIMESTAMPADD(SECOND,300,CURRENT_TIMESTAMP(6)),
          lifecycle_state='DEAD',death_reason='COMPLETED',dead_at=CURRENT_TIMESTAMP(6),
          auth_invalidation_state='REVOKED',closed_at=CURRENT_TIMESTAMP(6) WHERE sandbox_id=?
        """,
        sandbox);
    assertThat(orders("", owner, sandbox).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(policies("?query=refund").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void readsAbsentProfileAndCartWithoutCreatingRowsOrLeakingCaseVariantOwner() throws Exception {
    String other = owner.toUpperCase(Locale.ROOT);
    jdbc.update(
        "INSERT INTO crm_profile(user_subject,display_name,loyalty_tier,preferences) VALUES (?,'Private profile','MEMBER',JSON_OBJECT('size','XL'))",
        other);
    jdbc.update("INSERT INTO shopping_cart(user_subject,cart_version) VALUES (?,7)", other);
    Map<String, Long> before = profileAndCartCounts();
    var profileResult =
        get("/internal/eval/shopping/preferences", "shopping:profile:read", owner, sandbox);
    assertThat(profileResult.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(profileResult.getHeaders().getCacheControl()).contains("no-store");
    JsonNode profile = profileResult.getBody();
    assertThat(profile.path("userId").asText()).isEqualTo(owner);
    assertThat(profile.path("displayName").isNull()).isTrue();
    assertThat(profile.path("loyaltyTier").asText()).isEqualTo("NONE");
    assertThat(profile.path("preferences").size()).isZero();
    var cartResult = get("/internal/eval/shopping/cart", "shopping:cart:read", owner, sandbox);
    assertThat(cartResult.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(cartResult.getHeaders().getCacheControl()).contains("no-store");
    JsonNode cart = cartResult.getBody();
    assertThat(cart.path("version").asLong()).isZero();
    assertThat(cart.path("items").size()).isZero();
    assertThat(cart.path("currency").isNull()).isTrue();
    assertThat(cart.path("subtotalMinor").asLong()).isZero();
    assertThat(cart.path("checkoutReady").asBoolean()).isFalse();
    assertThat(profileAndCartCounts()).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM shopping_cart WHERE user_subject=BINARY ?",
                Long.class,
                owner))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_profile WHERE user_subject=BINARY ?", Long.class, owner))
        .isZero();
  }

  @Test
  void searchesActualPublishedPolicyAndRejectsInvalidQueries() throws Exception {
    String marker = "evalpolicy" + UUID.randomUUID().toString().replace("-", "");
    String published = "retail-policy-" + marker;
    String draft = "retail-guide-" + marker;
    faqIds.add(published);
    faqIds.add(draft);
    var repository = new FaqRepository(jdbc);
    var publication =
        new FaqPublicationService(
            repository, new FaqKnowledgeEventCodec(mapper), Clock.systemUTC());
    new FaqFixturePublisher(repository, publication, transactionManager)
        .publish(
            List.of(
                new FaqFixturePublisher.Entry(
                    published, marker + " refund", "Refunds require an eligible paid order.")));
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored ->
                publication.saveDraft(draft, marker + " private", "Unpublished proposal.", 0));
    var result = policies("?query=" + marker);
    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(result.getHeaders().getCacheControl()).contains("no-store");
    assertThat(result.getBody().size()).isEqualTo(1);
    JsonNode policy = result.getBody().get(0);
    assertThat(policy.path("policyId").asText()).isEqualTo(published);
    assertThat(policy.path("content").asText())
        .isEqualTo("Refunds require an eligible paid order.");
    assertThat(policy.path("publicationVersion").asLong()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM faq_publication_command WHERE faq_id=?",
                Long.class,
                published))
        .isEqualTo(1);
    for (String query :
        List.of(
            "",
            "?query=",
            "?query=" + "x".repeat(201),
            "?query=a+b+c+d+e+f+g+h+i",
            "?query=a&query=b",
            "?query=a&owner=other")) {
      assertThat(policies(query).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
  }

  private Map<String, Long> profileAndCartCounts() {
    Map<String, Long> result = new HashMap<>();
    for (String table :
        List.of("crm_profile", "shopping_cart", "shopping_cart_item", "shopping_cart_command")) {
      result.put(
          table,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM " + table + " WHERE user_subject IN (?,?)",
              Long.class,
              owner,
              owner.toUpperCase(Locale.ROOT)));
    }
    return result;
  }

  private String standard(String subject, String status, Instant created, String sandboxId) {
    String id = UUID.randomUUID().toString();
    orderIds.add(id);
    jdbc.update(
        """
        INSERT INTO standard_order (order_id,user_subject,sandbox_id,evaluation_owner_handle,
          product_id,product_name,unit_price_minor,currency,quantity,total_price_minor,
          product_version,status,state_version,created_at)
        VALUES (?,?,?,?,?,'Historical product',1250,'CNY',2,2500,7,?,?,?)
        """,
        id,
        subject,
        sandboxId,
        sandboxId == null ? null : "h".repeat(43),
        "retired-" + id,
        status,
        status.equals("PAID") ? 2 : 1,
        Timestamp.from(created));
    return id;
  }

  private String seckill(Instant created) {
    String id = UUID.randomUUID().toString();
    seckillIds.add(id);
    jdbc.update(
        """
        INSERT INTO seckill_order (order_id,reservation_id,transaction_event_id,timeout_event_id,
          user_subject,activity_id,product_id,product_name,unit_price_minor,currency,quantity,
          total_price_minor,status,state_version,unpaid_deadline,created_at)
        VALUES (?,?,?,?,?,?,?,'Historical seckill',1250,'CNY',2,2500,'UNPAID',1,?,?)
        """,
        id,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        owner,
        "activity-" + id,
        "retired-" + id,
        Timestamp.from(Instant.now().plusSeconds(3600)),
        Timestamp.from(created));
    return id;
  }

  private String payment(
      String order, String subject, String state, String sandboxId, long refunded) {
    String id = UUID.randomUUID().toString();
    attemptIds.add(id);
    jdbc.update(
        """
        INSERT INTO mock_payment_attempt (attempt_id,callback_correlation_id,user_subject,
          order_id,order_kind,sandbox_id,request_idempotency_key,intent_hash,amount_minor,
          refunded_amount_minor,currency,state,state_version,succeeded_at)
        VALUES (?,?,?,?,'STANDARD',?,?,?,2500,?,'CNY',?,?,?)
        """,
        id,
        UUID.randomUUID().toString(),
        subject,
        order,
        sandboxId,
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
    refundIds.add(id);
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
    String id = "eval-read-" + UUID.randomUUID();
    sandboxIds.add(id);
    jdbc.update(
        """
        INSERT INTO eval_sandbox (sandbox_id,case_correlation,reset_idempotency_key,fixture_digest,
          fixture_count,test_user_label,requested_ttl_seconds,auth_provision_idempotency_key,
          auth_revoke_idempotency_key,opaque_handle,lifecycle_state,auth_invalidation_state,
          provisioning_due_at,auth_expiry_upper_bound,expires_at,activated_at)
        VALUES (?,?,?, ?,1,?,3600,?,?,?,'ACTIVE','PROVISIONED',
          TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),TIMESTAMPADD(SECOND,3600,CURRENT_TIMESTAMP(6)),
          TIMESTAMPADD(SECOND,3600,CURRENT_TIMESTAMP(6)),CURRENT_TIMESTAMP(6))
        """,
        id,
        id,
        id,
        "c".repeat(64),
        owner,
        id + "-provision",
        id + "-revoke",
        UUID.randomUUID().toString().replace("-", "") + "h".repeat(11));
    return id;
  }

  private ResponseEntity<JsonNode> orders(String suffix, String subject, String sandboxId)
      throws Exception {
    return get(
        "/internal/eval/shopping/orders" + suffix, "shopping:orders:read", subject, sandboxId);
  }

  private ResponseEntity<JsonNode> get(String path, String scope, String subject, String sandboxId)
      throws Exception {
    var claims =
        claims(subject, "commerce-service")
            .claim("user_id", subject)
            .claim("session", session)
            .claim("scope", scope)
            .claim("token_type", "agent_obo")
            .claim("act", Map.of("azp", "shopping-agent"))
            .claim("sandbox", sandboxId);
    var headers = signedHeaders(claims, sandboxId);
    headers.set("X-Shopping-Session-Id", session);
    return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
  }

  private ResponseEntity<JsonNode> policies(String query) throws Exception {
    var claims =
        claims(owner, "citybuddy-web")
            .claim("token_type", "eval_direct_user")
            .claim("principal_state", "ACTIVE")
            .claim("permissions", List.of("shopping:session:create"))
            .claim("sandbox", sandbox)
            .claim("evaluation_handle", "h".repeat(43));
    return http.exchange(
        "/internal/eval/shopping/policies" + query,
        HttpMethod.GET,
        new HttpEntity<>(signedHeaders(claims, sandbox)),
        JsonNode.class);
  }

  private JWTClaimsSet.Builder claims(String subject, String audience) {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .issuer("https://identity.citybuddy.test")
        .audience(audience)
        .subject(subject)
        .issueTime(Date.from(now))
        .notBeforeTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(300)))
        .jwtID(UUID.randomUUID().toString());
  }

  private HttpHeaders signedHeaders(JWTClaimsSet.Builder claims, String sandboxId)
      throws Exception {
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("catalog-current").build(),
            claims.build());
    jwt.sign(new RSASSASigner(signingKey));
    var headers = new HttpHeaders();
    headers.setBearerAuth(jwt.serialize());
    headers.set("X-Eval-Sandbox-Id", sandboxId);
    return headers;
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }
}
