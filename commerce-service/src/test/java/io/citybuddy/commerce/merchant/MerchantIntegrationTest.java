package io.citybuddy.commerce.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.citybuddy.commerce.merchant.MerchantModels.Context;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MerchantIntegrationTest {
  private static final String DRAFTS = "/internal/merchant/price-drafts";

  @DynamicPropertySource
  static void integrationProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
    registry.add("spring.data.redis.url", () -> required("CATALOG_REDIS_URL"));
    registry.add("citybuddy.catalog.enabled", () -> "true");
    registry.add("citybuddy.catalog.issuer", () -> "https://identity.citybuddy.test");
    registry.add("citybuddy.catalog.user-audience", () -> "citybuddy-web");
    registry.add("citybuddy.catalog.jwks-url", () -> required("IDENTITY_JWKS_URL"));
    registry.add("citybuddy.catalog.required-permission", () -> "catalog:read");
    registry.add("citybuddy.catalog.worker-initial-delay-ms", () -> "3600000");
    registry.add("citybuddy.catalog.worker-delay-ms", () -> "3600000");
    registry.add("citybuddy.catalog.rocketmq-endpoints", () -> required("ROCKETMQ_ENDPOINTS"));
    registry.add("citybuddy.catalog.rocketmq-topic", () -> required("ROCKETMQ_TOPIC"));
    registry.add(
        "citybuddy.catalog.rocketmq-consumer-group", () -> required("ROCKETMQ_CONSUMER_GROUP"));
    registry.add("citybuddy.obo.enabled", () -> "true");
    registry.add("citybuddy.obo.issuer", () -> "https://identity.citybuddy.test");
    registry.add("citybuddy.obo.jwks-url", () -> required("IDENTITY_JWKS_URL"));
    registry.add("citybuddy.merchant.enabled", () -> "true");
  }

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private MerchantRepository repository;
  private String operator;
  private String session;
  private List<String> products;
  private RSAPrivateKey signingKey;

  @BeforeEach
  void seedOwnedProducts() throws Exception {
    String prefix = "merchant-it-" + UUID.randomUUID().toString().substring(0, 8);
    operator = prefix + "-operator";
    session = prefix + "-session";
    products = List.of(prefix + "-a", prefix + "-b", prefix + "-c");
    for (String productId : products) {
      jdbc.update(
          """
          INSERT INTO product
            (product_id, name, description, price_minor, currency, stock_quantity,
             available, publication_state, publication_version)
          VALUES (?, ?, 'Merchant integration fixture', 1000, 'AUD', 7, TRUE, 'PUBLISHED', 1)
          """,
          productId,
          productId + " name");
    }
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
  void prepareReturnsTheAuthoritativeSnapshotWithoutChangingProductsOrPublishingEvents()
      throws Exception {
    JsonNode draft = prepare("prepare-only", List.of(item(0, 1100), item(1, 1200)));

    assertThat(draft.path("state").asText()).isEqualTo("PREPARED");
    assertThat(draft.path("items").size()).isEqualTo(2);
    assertThat(draft.path("items").get(0).path("oldPriceMinor").asLong()).isEqualTo(1000);
    assertThat(draft.path("items").get(0).path("expectedVersion").asLong()).isEqualTo(1);
    assertThat(draft.path("items").get(0).path("name").asText())
        .isEqualTo(products.getFirst() + " name");
    assertThat(storedState(draft)).isEqualTo("PREPARED");
    assertProductsUnchanged();

    ResponseEntity<JsonNode> product =
        exchange(
            HttpMethod.GET,
            "/internal/merchant/products/" + products.getFirst(),
            obo("merchant:read"),
            session,
            null,
            null);
    assertThat(product.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(product.getBody().path("priceMinor").asLong()).isEqualTo(1000);
    assertThat(product.getBody().path("priceEditable").asBoolean()).isTrue();
  }

  @Test
  void internalRoutesRejectTheWrongActorScopeAndSessionHeader() throws Exception {
    Object body = proposal(List.of(item(0, 1100)));
    for (String credential :
        List.of(
            obo(operator, session, "merchant:price:prepare", "agent-service"),
            obo("merchant:read"),
            direct(operator, List.of("merchant:price:apply")))) {
      assertThat(
              exchange(HttpMethod.POST, DRAFTS, credential, session, "forbidden", body)
                  .getStatusCode())
          .isEqualTo(HttpStatus.FORBIDDEN);
    }
    assertThat(
            exchange(
                    HttpMethod.POST,
                    DRAFTS,
                    obo("merchant:price:prepare"),
                    session + "-other",
                    "wrong-session-header",
                    body)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            exchange(
                    HttpMethod.POST,
                    DRAFTS,
                    obo("merchant:price:prepare"),
                    null,
                    "missing-session-header",
                    body)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(draftCount()).isZero();
    assertProductsUnchanged();
  }

  @Test
  void draftsRemainBoundToTheirOperatorAndMerchantSession() throws Exception {
    JsonNode draft = prepare("owned", List.of(item(0, 1100)));
    for (String path : List.of(draftPath(draft), draftPath(draft) + "/cancel")) {
      HttpMethod method = path.endsWith("/cancel") ? HttpMethod.POST : HttpMethod.GET;
      String scope = path.endsWith("/cancel") ? "merchant:price:cancel" : "merchant:price:read";
      assertThat(
              exchange(
                      method,
                      path,
                      obo(operator + "-other", session, scope, "merchant-agent"),
                      session,
                      null,
                      null)
                  .getStatusCode())
          .isEqualTo(HttpStatus.NOT_FOUND);
      assertThat(
              exchange(
                      method,
                      path,
                      obo(operator, session + "-other", scope, "merchant-agent"),
                      session + "-other",
                      null,
                      null)
                  .getStatusCode())
          .isEqualTo(HttpStatus.NOT_FOUND);
    }
    assertThat(
            apply(draft, direct(operator + "-other", List.of("merchant:price:apply")))
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(read(draft)).isEqualTo(draft);
    assertProductsUnchanged();
  }

  @Test
  void applyRequiresTheDirectOperatorPermissionAndRejectsAnOboCredential() throws Exception {
    JsonNode draft = prepare("direct-only", List.of(item(0, 1100)));

    assertThat(apply(draft, obo("merchant:price:apply")).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(apply(draft, direct(operator, List.of("merchant:session:create"))).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(apply(draft, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(storedState(draft)).isEqualTo("PREPARED");
    assertProductsUnchanged();
  }

  @Test
  void threeProductApprovalCommitsVersionsEventsAndAReplayableResultTogether() throws Exception {
    JsonNode draft =
        prepare("three-products", List.of(item(2, 1300), item(0, 1100), item(1, 1200)));
    ResponseEntity<JsonNode> approved = apply(draft, directOperator());

    assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode result = approved.getBody();
    assertThat(result.path("state").asText()).isEqualTo("APPLIED");
    assertThat(result.path("result").path("changes").size()).isEqualTo(3);
    for (int index = 0; index < products.size(); index++) {
      assertThat(truth(products.get(index))).isEqualTo(new ProductTruth(1100 + index * 100, 2, 7));
      assertThat(publicationCount(products.get(index))).isEqualTo(1);
      JsonNode change = result.path("result").path("changes").get(index);
      assertThat(change.path("oldVersion").asLong()).isEqualTo(1);
      assertThat(change.path("newVersion").asLong()).isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "SELECT aggregate_id FROM commerce_outbox WHERE event_id = ?",
                  String.class,
                  change.path("eventId").asText()))
          .isEqualTo(products.get(index));
    }
    assertThat(storedState(draft)).isEqualTo("APPLIED");
    assertThat(read(draft)).isEqualTo(result);
    assertThat(apply(draft, directOperator()).getBody()).isEqualTo(result);
    for (String product : products) {
      assertThat(publicationCount(product)).isEqualTo(1);
    }
  }

  @Test
  void aStaleVersionRejectsTheEntireBatchAndPersistsTheRejectionForReplay() throws Exception {
    JsonNode draft = prepare("stale-batch", List.of(item(0, 1100), item(1, 1200), item(2, 1300)));
    jdbc.update(
        "UPDATE product SET price_minor = 1050, publication_version = 2 WHERE product_id = ?",
        products.get(1));
    ResponseEntity<JsonNode> rejected = apply(draft, directOperator());

    assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(rejected.getBody().path("state").asText()).isEqualTo("REJECTED");
    assertThat(rejected.getBody().path("result").path("reason").asText())
        .isEqualTo("VERSION_CONFLICT");
    assertThat(storedState(draft)).isEqualTo("REJECTED");
    assertThat(truth(products.get(0))).isEqualTo(new ProductTruth(1000, 1, 7));
    assertThat(truth(products.get(1))).isEqualTo(new ProductTruth(1050, 2, 7));
    assertThat(truth(products.get(2))).isEqualTo(new ProductTruth(1000, 1, 7));
    for (String product : products) {
      assertThat(publicationCount(product)).isZero();
    }
    assertThat(read(draft)).isEqualTo(rejected.getBody());
    ResponseEntity<JsonNode> replay = apply(draft, directOperator());
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(replay.getBody()).isEqualTo(rejected.getBody());
  }

  @Test
  void concurrentApprovalsIncrementTheProductAndEmitItsEventOnlyOnce() throws Exception {
    JsonNode draft = prepare("concurrent-apply", List.of(item(0, 1100)));
    String credential = directOperator();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return apply(draft, credential);
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return apply(draft, credential);
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      ResponseEntity<JsonNode> firstResult = first.get(20, TimeUnit.SECONDS);
      ResponseEntity<JsonNode> secondResult = second.get(20, TimeUnit.SECONDS);

      assertThat(firstResult.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(secondResult.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(secondResult.getBody()).isEqualTo(firstResult.getBody());
      assertThat(read(draft)).isEqualTo(firstResult.getBody());
    } finally {
      start.countDown();
    }
    assertThat(truth(products.getFirst())).isEqualTo(new ProductTruth(1100, 2, 7));
    assertThat(publicationCount(products.getFirst())).isEqualTo(1);
    assertThat(storedState(draft)).isEqualTo("APPLIED");
  }

  @Test
  void cancellationIsReplayableAndPreventsLaterApproval() throws Exception {
    JsonNode draft = prepare("cancelled", List.of(item(0, 1100)));
    ResponseEntity<JsonNode> cancelled =
        exchange(
            HttpMethod.POST,
            draftPath(draft) + "/cancel",
            obo("merchant:price:cancel"),
            session,
            null,
            Map.of());

    assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(cancelled.getBody().path("state").asText()).isEqualTo("CANCELLED");
    assertThat(storedState(draft)).isEqualTo("CANCELLED");
    ResponseEntity<JsonNode> approved = apply(draft, directOperator());
    assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(approved.getBody()).isEqualTo(cancelled.getBody());
    assertThat(read(draft)).isEqualTo(cancelled.getBody());
    assertThat(
            exchange(
                    HttpMethod.POST,
                    draftPath(draft) + "/cancel",
                    obo("merchant:price:cancel"),
                    session,
                    null,
                    null)
                .getBody())
        .isEqualTo(cancelled.getBody());
    assertProductsUnchanged();
  }

  @Test
  void prepareReplaysTheSameIntentAndRejectsAnotherIntentUnderTheSameKey() throws Exception {
    JsonNode draft = prepare("same-key", List.of(item(0, 1100), item(1, 1200)));

    assertThat(prepare("same-key", List.of(item(1, 1200), item(0, 1100)))).isEqualTo(draft);
    ResponseEntity<JsonNode> conflict =
        exchange(
            HttpMethod.POST,
            DRAFTS,
            obo("merchant:price:prepare"),
            session,
            "same-key",
            proposal(List.of(item(0, 1150), item(1, 1200))));
    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody().path("category").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(draftCount()).isEqualTo(1);
    assertProductsUnchanged();

    JsonNode applied = apply(draft, directOperator()).getBody();
    assertThat(prepare("same-key", List.of(item(0, 1100), item(1, 1200)))).isEqualTo(applied);
    assertThat(draftCount()).isEqualTo(1);
  }

  @Test
  void caseDistinctMerchantSessionsCreateIndependentDraftsAndCannotReadEachOthersDraft()
      throws Exception {
    JsonNode first = prepare("case-sensitive-session", List.of(item(0, 1100)));
    String otherSession = session.toUpperCase(Locale.ROOT);
    ResponseEntity<JsonNode> second =
        exchange(
            HttpMethod.POST,
            DRAFTS,
            obo(operator, otherSession, "merchant:price:prepare", "merchant-agent"),
            otherSession,
            "case-sensitive-session",
            proposal(List.of(item(0, 1100))));

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getBody().path("draftId").asText())
        .isNotEqualTo(first.path("draftId").asText());
    assertThat(draftCount()).isEqualTo(2);
    assertThat(
            exchange(
                    HttpMethod.GET,
                    draftPath(first),
                    obo(operator, otherSession, "merchant:price:read", "merchant-agent"),
                    otherSession,
                    null,
                    null)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            exchange(
                    HttpMethod.GET,
                    draftPath(second.getBody()),
                    obo("merchant:price:read"),
                    session,
                    null,
                    null)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertProductsUnchanged();
  }

  @Test
  void aConcurrentPrepareWinnerIsReplayedAfterTheProductBecomesUneditable() throws Exception {
    Context context = new Context(operator, session);
    String key = "concurrent-prepare-winner";
    CountDownLatch firstMiss = new CountDownLatch(1);
    CountDownLatch winnerCommitted = new CountDownLatch(1);
    AtomicBoolean pauseFirstLookup = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              Object found = invocation.callRealMethod();
              if (pauseFirstLookup.compareAndSet(true, false)) {
                assertThat(found).isEqualTo(Optional.empty());
                firstMiss.countDown();
                assertThat(winnerCommitted.await(30, TimeUnit.SECONDS)).isTrue();
              }
              return found;
            })
        .when(repository)
        .findByRequest(context, key);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var waiting = executor.submit(() -> prepare(key, List.of(item(0, 1100))));
      JsonNode winner;
      try {
        assertThat(firstMiss.await(15, TimeUnit.SECONDS)).isTrue();
        winner = prepare(key, List.of(item(0, 1100)));
        jdbc.update(
            "UPDATE product SET publication_state = 'UNPUBLISHED', publication_version = 2 WHERE product_id = ?",
            products.getFirst());
      } finally {
        winnerCommitted.countDown();
      }

      assertThat(waiting.get(30, TimeUnit.SECONDS)).isEqualTo(winner);
      assertThat(draftCount()).isEqualTo(1);
      assertThat(read(winner)).isEqualTo(winner);
      verify(repository, times(1))
          .insertOrReplay(
              anyString(),
              eq(context),
              eq(key),
              anyString(),
              eq("AUD"),
              anyList(),
              any(Instant.class));
    } finally {
      winnerCommitted.countDown();
    }
    assertThat(truth(products.getFirst())).isEqualTo(new ProductTruth(1000, 2, 7));
    assertThat(publicationCount(products.getFirst())).isZero();
  }

  @Test
  void twoSpellingsOfOneStoredProductCannotCreateAnUnapprovableDraft() throws Exception {
    ResponseEntity<JsonNode> response =
        exchange(
            HttpMethod.POST,
            DRAFTS,
            obo("merchant:price:prepare"),
            session,
            "duplicate-canonical-product",
            proposal(
                List.of(
                    item(0, 1100),
                    Map.of(
                        "productId",
                        products.getFirst().toUpperCase(Locale.ROOT),
                        "newPriceMinor",
                        1200))));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(draftCount()).isZero();
    assertProductsUnchanged();
  }

  private JsonNode prepare(String key, List<Map<String, Object>> items) throws Exception {
    ResponseEntity<JsonNode> response =
        exchange(
            HttpMethod.POST, DRAFTS, obo("merchant:price:prepare"), session, key, proposal(items));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  private JsonNode read(JsonNode draft) throws Exception {
    ResponseEntity<JsonNode> response =
        exchange(HttpMethod.GET, draftPath(draft), obo("merchant:price:read"), session, null, null);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody();
  }

  private ResponseEntity<JsonNode> apply(JsonNode draft, String credential) {
    return exchange(
        HttpMethod.POST,
        "/api/merchant/price-drafts/" + draft.path("draftId").asText() + "/apply",
        credential,
        null,
        null,
        Map.of());
  }

  private ResponseEntity<JsonNode> exchange(
      HttpMethod method, String path, String token, String sessionId, String key, Object body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      headers.setBearerAuth(token);
    }
    if (sessionId != null) {
      headers.set("X-Merchant-Session-Id", sessionId);
    }
    if (key != null) {
      headers.set("Idempotency-Key", key);
    }
    return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
  }

  private Map<String, Object> item(int productIndex, long price) {
    return Map.of("productId", products.get(productIndex), "newPriceMinor", price);
  }

  private static Map<String, Object> proposal(List<Map<String, Object>> items) {
    return Map.of("currency", "AUD", "items", items);
  }

  private static String draftPath(JsonNode draft) {
    return DRAFTS + "/" + draft.path("draftId").asText();
  }

  private ProductTruth truth(String productId) {
    return jdbc.queryForObject(
        "SELECT price_minor, publication_version, stock_quantity FROM product WHERE product_id = ?",
        (row, index) ->
            new ProductTruth(
                row.getLong("price_minor"),
                row.getLong("publication_version"),
                row.getLong("stock_quantity")),
        productId);
  }

  private long publicationCount(String productId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type = 'PRODUCT' AND aggregate_id = ?",
        Long.class,
        productId);
  }

  private String storedState(JsonNode draft) {
    return jdbc.queryForObject(
        "SELECT state FROM merchant_price_draft WHERE draft_id = ?",
        String.class,
        draft.path("draftId").asText());
  }

  private long draftCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM merchant_price_draft WHERE operator_subject = ?",
        Long.class,
        operator);
  }

  private void assertProductsUnchanged() {
    for (String product : products) {
      assertThat(truth(product)).isEqualTo(new ProductTruth(1000, 1, 7));
      assertThat(publicationCount(product)).isZero();
    }
  }

  private String obo(String scope) throws Exception {
    return obo(operator, session, scope, "merchant-agent");
  }

  private String obo(String subject, String sessionId, String scope, String actor)
      throws Exception {
    return sign(
        claims(subject)
            .audience("commerce-service")
            .claim("token_type", "agent_obo")
            .claim("user_id", subject)
            .claim("session", sessionId)
            .claim("scope", scope)
            .claim("act", Map.of("azp", actor))
            .build());
  }

  private String directOperator() throws Exception {
    return direct(operator, List.of("merchant:price:apply"));
  }

  private String direct(String subject, List<String> permissions) throws Exception {
    return sign(
        claims(subject)
            .audience("citybuddy-web")
            .claim("token_type", "direct_user")
            .claim("principal_state", "ACTIVE")
            .claim("permissions", permissions)
            .build());
  }

  private static JWTClaimsSet.Builder claims(String subject) {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .issuer("https://identity.citybuddy.test")
        .subject(subject)
        .issueTime(Date.from(now))
        .notBeforeTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(300)))
        .jwtID(UUID.randomUUID().toString());
  }

  private String sign(JWTClaimsSet claims) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("catalog-current").build(), claims);
    jwt.sign(new RSASSASigner(signingKey));
    return jwt.serialize();
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }

  private record ProductTruth(long price, long version, long stock) {}
}
