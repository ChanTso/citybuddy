package io.citybuddy.commerce.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.citybuddy.commerce.order.OrderCategory;
import io.citybuddy.commerce.order.OrderException;
import io.citybuddy.commerce.order.OrderProperties;
import io.citybuddy.commerce.order.OrderRepository;
import io.citybuddy.commerce.order.OrderRequest;
import io.citybuddy.commerce.order.OrderResult;
import io.citybuddy.commerce.order.OrderService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class CatalogIntegrationTest {
  private static final String VISIBLE_ID = "catalog-visible";
  private static final String HIDDEN_ID = "catalog-hidden";

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
    registry.add("citybuddy.catalog.jwks-cache-ttl", () -> "30s");
    registry.add("citybuddy.catalog.clock-skew", () -> "30s");
    registry.add("citybuddy.catalog.required-permission", () -> "catalog:read");
    registry.add("citybuddy.orders.enabled", () -> "true");
    registry.add("citybuddy.orders.required-permission", () -> "order:create");
    registry.add("citybuddy.orders.maximum-quantity", () -> "100");
    registry.add("citybuddy.orders.maximum-concurrency-attempts", () -> "10");
    registry.add("citybuddy.catalog.cache-ttl", () -> "30s");
    registry.add("citybuddy.catalog.cache-jitter", () -> "10s");
    registry.add("citybuddy.catalog.null-ttl", () -> "3s");
    registry.add("citybuddy.catalog.mutex-ttl", () -> "2s");
    registry.add("citybuddy.catalog.worker-initial-delay-ms", () -> "3600000");
    registry.add("citybuddy.catalog.worker-delay-ms", () -> "3600000");
    registry.add("citybuddy.catalog.rocketmq-endpoints", () -> required("ROCKETMQ_ENDPOINTS"));
    registry.add("citybuddy.catalog.rocketmq-topic", () -> required("ROCKETMQ_TOPIC"));
    registry.add(
        "citybuddy.catalog.rocketmq-consumer-group", () -> required("ROCKETMQ_CONSUMER_GROUP"));
  }

  @LocalServerPort private int port;
  @Autowired private CatalogProperties properties;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ProductRepository repository;
  @Autowired private ProductCache cache;
  @Autowired private ProductPublicationService publicationService;
  @Autowired private CatalogEventWorker eventWorker;
  @Autowired private ProductInvalidationHandler invalidationHandler;
  @Autowired private RocketMqCatalogMessaging messaging;
  @Autowired private StringRedisTemplate redis;
  @Autowired private TestRestTemplate rest;
  @Autowired private TransactionTemplate transactions;
  @Autowired private OrderRepository orderRepository;

  @Test
  void provesCatalogTruthCacheOutboxAndNormalEventRecovery() throws Exception {
    seedPublishedAndUnpublishedProducts();
    proveAuthenticatedContractsAndLiveFields();
    proveRedisProtectionsAndMysqlFallback();
    provePublicationAtomicityAndNormalEventRecovery();
    proveProductionConsumerRejectsReservedEvaluationContext();
  }

  @Test
  void provesStandardOrderApiAtomicityIdempotencyAndConcurrency() throws Exception {
    seedOrderProduct("order-main", 10, true, "PUBLISHED", 4);
    ResponseEntity<JsonNode> created =
        postOrder(
            token(),
            "shared-key",
            Map.of("productId", "order-main", "quantity", 2, "expectedProductVersion", 4));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String orderId = created.getBody().get("orderId").asText();
    assertThat(created.getBody().get("unitPriceMinor").asLong()).isEqualTo(750);
    assertThat(created.getBody().get("totalPriceMinor").asLong()).isEqualTo(1500);
    assertThat(created.getBody().get("replayed").asBoolean()).isFalse();
    assertOrderCardinality("order-main", 8, 1, 1, 1);
    assertTotalIdempotencyCount(1);

    ResponseEntity<JsonNode> replay =
        postOrder(
            token(),
            "shared-key",
            Map.of("productId", "order-main", "quantity", 2, "expectedProductVersion", 4));
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getBody().get("orderId").asText()).isEqualTo(orderId);
    assertThat(replay.getBody().get("replayed").asBoolean()).isTrue();
    assertOrderCardinality("order-main", 8, 1, 1, 1);
    assertTotalIdempotencyCount(1);

    assertOrderError(
        postOrder(
            token(),
            "shared-key",
            Map.of("productId", "order-main", "quantity", 1, "expectedProductVersion", 4)),
        HttpStatus.CONFLICT,
        "IDEMPOTENCY_CONFLICT");
    ResponseEntity<JsonNode> otherUser =
        postOrder(
            otherToken(),
            "shared-key",
            Map.of("productId", "order-main", "quantity", 1, "expectedProductVersion", 4));
    assertThat(otherUser.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(otherUser.getBody().get("orderId").asText()).isNotEqualTo(orderId);
    assertTotalIdempotencyCount(2);

    assertOrderError(
        postOrder(
            null,
            "auth-key",
            Map.of("productId", "order-main", "quantity", 1, "expectedProductVersion", 4)),
        HttpStatus.UNAUTHORIZED,
        "AUTHENTICATION");
    assertOrderError(
        postOrder(
            signedTestToken(
                "https://wrong-identity.citybuddy.test", "citybuddy-web", "direct_user"),
            "wrong-issuer-key",
            Map.of("productId", "order-main", "quantity", 1, "expectedProductVersion", 4)),
        HttpStatus.UNAUTHORIZED,
        "AUTHENTICATION");
    assertOrderError(
        postOrder(
            signedTestToken("https://identity.citybuddy.test", "commerce-service", "direct_user"),
            "wrong-audience-key",
            Map.of("productId", "order-main", "quantity", 1, "expectedProductVersion", 4)),
        HttpStatus.UNAUTHORIZED,
        "AUTHENTICATION");
    assertOrderError(
        postOrder(
            signedTestToken("https://identity.citybuddy.test", "citybuddy-web", "agent_obo"),
            "wrong-type-key",
            Map.of("productId", "order-main", "quantity", 1, "expectedProductVersion", 4)),
        HttpStatus.UNAUTHORIZED,
        "AUTHENTICATION");
    assertOrderError(
        postOrder(
            limitedToken(),
            "permission-key",
            Map.of("productId", "order-main", "quantity", 1, "expectedProductVersion", 4)),
        HttpStatus.FORBIDDEN,
        "AUTHORIZATION");
    assertOrderError(
        postOrder(
            token(),
            "owner-key",
            Map.of(
                "productId",
                "order-main",
                "quantity",
                1,
                "expectedProductVersion",
                4,
                "userSubject",
                "other-user")),
        HttpStatus.FORBIDDEN,
        "OWNERSHIP");
    assertOrderError(
        postOrder(
            token(),
            "price-key",
            Map.of(
                "productId",
                "order-main",
                "quantity",
                1,
                "expectedProductVersion",
                4,
                "priceMinor",
                1)),
        HttpStatus.BAD_REQUEST,
        "VALIDATION");
    assertOrderError(
        postOrder(
            token(),
            "malformed-key",
            Map.of("productId", "order-main", "quantity", "one", "expectedProductVersion", 4)),
        HttpStatus.BAD_REQUEST,
        "VALIDATION");
    assertOrderError(
        postOrder(
            token(),
            "stock-authority-key",
            Map.of(
                "productId",
                "order-main",
                "quantity",
                1,
                "expectedProductVersion",
                4,
                "stockQuantity",
                999)),
        HttpStatus.BAD_REQUEST,
        "VALIDATION");
    assertOrderError(
        postOrder(
            token(),
            null,
            Map.of("productId", "order-main", "quantity", 1, "expectedProductVersion", 4)),
        HttpStatus.BAD_REQUEST,
        "VALIDATION");
    assertOrderError(
        postOrder(
            token(),
            "quantity-key",
            Map.of("productId", "order-main", "quantity", 0, "expectedProductVersion", 4)),
        HttpStatus.BAD_REQUEST,
        "VALIDATION");
    assertOrderError(
        postOrder(
            token(),
            "stale-key",
            Map.of("productId", "order-main", "quantity", 1, "expectedProductVersion", 3)),
        HttpStatus.CONFLICT,
        "STALE_VERSION");
    assertOrderError(
        postOrder(
            token(),
            "stock-key",
            Map.of("productId", "order-main", "quantity", 100, "expectedProductVersion", 4)),
        HttpStatus.CONFLICT,
        "INSUFFICIENT_STOCK");
    assertOrderError(
        postOrder(
            token(),
            "missing-key",
            Map.of("productId", "order-missing", "quantity", 1, "expectedProductVersion", 1)),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "VALIDATION");
    seedOrderProduct("order-hidden", 5, true, "UNPUBLISHED", 1);
    assertOrderError(
        postOrder(
            token(),
            "hidden-key",
            Map.of("productId", "order-hidden", "quantity", 1, "expectedProductVersion", 1)),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "VALIDATION");
    seedOrderProduct("order-unavailable", 5, false, "PUBLISHED", 1);
    assertOrderError(
        postOrder(
            token(),
            "unavailable-key",
            Map.of("productId", "order-unavailable", "quantity", 1, "expectedProductVersion", 1)),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "VALIDATION");
    assertOrderCardinality("order-main", 7, 2, 2, 2);
    assertTotalIdempotencyCount(2);
    assertIdempotencyKeysAbsent(
        "auth-key",
        "wrong-issuer-key",
        "wrong-audience-key",
        "wrong-type-key",
        "permission-key",
        "owner-key",
        "price-key",
        "stock-authority-key",
        "quantity-key",
        "malformed-key",
        "stale-key",
        "stock-key",
        "missing-key",
        "hidden-key",
        "unavailable-key");

    proveRealMysqlRollback();
    proveLimitedStockConcurrency();
    proveConcurrentDuplicateIdempotency();
    proveControlledRetryExhaustionClassifications();
    proveOrderDatabaseUnavailabilityClassification();
  }

  private void proveRealMysqlRollback() {
    seedOrderProduct("order-rollback", 5, true, "PUBLISHED", 1);
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      OrderRepository.ProductSnapshot product =
                          orderRepository.findProduct("order-rollback").orElseThrow();
                      orderRepository.reserveIdempotency(
                          "catalog-user",
                          "rollback-key",
                          "0".repeat(64),
                          "00000000-0000-0000-0000-000000000040");
                      assertThat(orderRepository.decrementStock(product, 2)).isTrue();
                      orderRepository.insertOrder(
                          "catalog-user", "00000000-0000-0000-0000-000000000040", product, 2);
                      throw new IllegalStateException("controlled failure before required Outbox");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled failure before required Outbox");
    assertOrderCardinality("order-rollback", 5, 0, 0, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_idempotency WHERE user_subject = 'catalog-user' AND idempotency_key = 'rollback-key'",
                Integer.class))
        .isZero();
  }

  private void proveLimitedStockConcurrency() throws Exception {
    seedOrderProduct("order-limited", 3, true, "PUBLISHED", 1);
    var executor = Executors.newFixedThreadPool(8);
    CountDownLatch ready = new CountDownLatch(8);
    CountDownLatch start = new CountDownLatch(1);
    List<java.util.concurrent.Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      int requestIndex = index;
      futures.add(
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return postOrder(
                    token(),
                    "limited-" + requestIndex,
                    Map.of(
                        "productId", "order-limited", "quantity", 1, "expectedProductVersion", 1));
              }));
    }
    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    int successes = 0;
    int insufficient = 0;
    for (var future : futures) {
      ResponseEntity<JsonNode> response = future.get(15, TimeUnit.SECONDS);
      if (response.getStatusCode() == HttpStatus.CREATED) {
        successes++;
      } else {
        assertOrderError(response, HttpStatus.CONFLICT, "INSUFFICIENT_STOCK");
        insufficient++;
      }
    }
    executor.shutdownNow();
    assertThat(successes).isEqualTo(3);
    assertThat(insufficient).isEqualTo(5);
    assertOrderCardinality("order-limited", 0, 3, 3, 3);
    assertTotalIdempotencyCount(5);
  }

  private void proveConcurrentDuplicateIdempotency() throws Exception {
    for (int round = 1; round <= 5; round++) {
      String productId = "order-duplicate-" + round;
      String idempotencyKey = "duplicate-key-" + round;
      seedOrderProduct(productId, 5, true, "PUBLISHED", 1);
      var executor = Executors.newFixedThreadPool(8);
      try {
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
          futures.add(
              executor.submit(
                  () -> {
                    ready.countDown();
                    start.await();
                    return postOrder(
                        token(),
                        idempotencyKey,
                        Map.of("productId", productId, "quantity", 1, "expectedProductVersion", 1));
                  }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int created = 0;
        int replayed = 0;
        String orderId = null;
        for (var future : futures) {
          ResponseEntity<JsonNode> response = future.get(15, TimeUnit.SECONDS);
          assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
          if (response.getStatusCode() == HttpStatus.CREATED) {
            created++;
          } else {
            replayed++;
          }
          String current = response.getBody().get("orderId").asText();
          if (orderId == null) {
            orderId = current;
          }
          assertThat(current).isEqualTo(orderId);
        }
        assertThat(created).isEqualTo(1);
        assertThat(replayed).isEqualTo(7);
      } finally {
        executor.shutdownNow();
      }
      assertOrderCardinality(productId, 4, 1, 1, 1);
    }
    assertTotalIdempotencyCount(10);
  }

  private void proveControlledRetryExhaustionClassifications() throws Exception {
    proveCommittedSiblingAfterControlledExhaustion();
    proveConflictingSiblingAfterControlledExhaustion();
    proveMissingTruthAfterControlledExhaustion();
    assertTotalIdempotencyCount(12);
  }

  private void proveCommittedSiblingAfterControlledExhaustion() throws Exception {
    String productId = "order-exhaustion-committed";
    String idempotencyKey = "exhaustion-committed-key";
    seedOrderProduct(productId, 5, true, "PUBLISHED", 1);
    ControlledExhaustion result =
        exhaustAgainstGapLock(
            productId,
            idempotencyKey,
            1,
            () -> postOrder(token(), idempotencyKey, orderIntent(productId, 1)));

    assertThat(result.sibling().getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(result.target().replayed()).isTrue();
    assertThat(result.target().orderId())
        .isEqualTo(result.sibling().getBody().get("orderId").asText());
    assertThat(result.finalResolutionReads()).isEqualTo(1);
    assertOrderCardinality(productId, 4, 1, 1, 1);
  }

  private void proveConflictingSiblingAfterControlledExhaustion() throws Exception {
    String productId = "order-exhaustion-conflict";
    String idempotencyKey = "exhaustion-conflict-key";
    seedOrderProduct(productId, 5, true, "PUBLISHED", 1);
    ControlledExhaustion result =
        exhaustAgainstGapLock(
            productId,
            idempotencyKey,
            1,
            () -> postOrder(token(), idempotencyKey, orderIntent(productId, 2)));

    assertThat(result.sibling().getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(result.failure()).isNotNull();
    assertThat(result.failure().status()).isEqualTo(409);
    assertThat(result.failure().category()).isEqualTo(OrderCategory.IDEMPOTENCY_CONFLICT);
    assertThat(result.finalResolutionReads()).isEqualTo(1);
    assertOrderCardinality(productId, 3, 1, 1, 1);
  }

  private void proveMissingTruthAfterControlledExhaustion() throws Exception {
    String productId = "order-exhaustion-missing";
    String idempotencyKey = "exhaustion-missing-key";
    seedOrderProduct(productId, 5, true, "PUBLISHED", 1);
    ControlledExhaustion result = exhaustAgainstGapLock(productId, idempotencyKey, 1, () -> null);

    assertThat(result.sibling()).isNull();
    assertThat(result.failure()).isNotNull();
    assertThat(result.failure().status()).isEqualTo(409);
    assertThat(result.failure().category()).isEqualTo(OrderCategory.CONCURRENCY_EXHAUSTED);
    assertThat(result.finalResolutionReads()).isEqualTo(1);
    assertOrderCardinality(productId, 5, 0, 0, 0);
  }

  private ControlledExhaustion exhaustAgainstGapLock(
      String productId,
      String idempotencyKey,
      int quantity,
      java.util.concurrent.Callable<ResponseEntity<JsonNode>> sibling)
      throws Exception {
    var targetDataSource =
        new DriverManagerDataSource(
            required("CATALOG_MYSQL_URL"), "commerce_app", required("MYSQL_COMMERCE_APP_PASSWORD"));
    JdbcTemplate targetJdbc = new JdbcTemplate(targetDataSource);
    ExhaustionProbeRepository probe = new ExhaustionProbeRepository(targetJdbc, objectMapper);
    OrderService oneAttemptService =
        new OrderService(
            probe,
            new TransactionTemplate(new DataSourceTransactionManager(targetDataSource)),
            new OrderProperties("order:create", 100, 1));
    OrderRequest request = orderRequest(productId, quantity);
    var executor = Executors.newSingleThreadExecutor();
    Future<OrderResult> target = null;
    ResponseEntity<JsonNode> siblingResponse = null;
    try (Connection blocker = lockMissingIdempotencyGap(idempotencyKey)) {
      target =
          executor.submit(
              () ->
                  oneAttemptService.create(
                      "catalog-user", idempotencyKey, request, "controlled-exhaustion"));
      assertThat(probe.awaitFinalResolution()).isTrue();
      blocker.rollback();
      siblingResponse = sibling.call();
      probe.allowFinalResolution();
      try {
        return new ControlledExhaustion(
            target.get(10, TimeUnit.SECONDS), null, siblingResponse, probe.finalResolutionReads());
      } catch (java.util.concurrent.ExecutionException exception) {
        assertThat(exception.getCause()).isInstanceOf(OrderException.class);
        return new ControlledExhaustion(
            null,
            (OrderException) exception.getCause(),
            siblingResponse,
            probe.finalResolutionReads());
      }
    } finally {
      probe.allowFinalResolution();
      if (target != null && !target.isDone()) {
        target.cancel(true);
      }
      executor.shutdownNow();
    }
  }

  private Connection lockMissingIdempotencyGap(String idempotencyKey) throws Exception {
    Connection blocker =
        DriverManager.getConnection(
            required("CATALOG_MYSQL_URL"), "root", required("MYSQL_BOOTSTRAP_PASSWORD"));
    blocker.setAutoCommit(false);
    blocker.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    try (var statement =
        blocker.prepareStatement(
            "SELECT intent_hash FROM order_idempotency WHERE user_subject = ? AND idempotency_key = ? FOR UPDATE")) {
      statement.setString(1, "catalog-user");
      statement.setString(2, idempotencyKey);
      statement.executeQuery();
    }
    return blocker;
  }

  private static Map<String, Object> orderIntent(String productId, int quantity) {
    return Map.of("productId", productId, "quantity", quantity, "expectedProductVersion", 1);
  }

  private static OrderRequest orderRequest(String productId, int quantity) {
    OrderRequest request = new OrderRequest();
    request.setProductId(productId);
    request.setQuantity(quantity);
    request.setExpectedProductVersion(1L);
    return request;
  }

  private void proveOrderDatabaseUnavailabilityClassification() throws Exception {
    String productId = "order-database-outage";
    String idempotencyKey = "database-outage-key";
    Map<String, Object> intent =
        Map.of("productId", productId, "quantity", 1, "expectedProductVersion", 1);
    seedOrderProduct(productId, 5, true, "PUBLISHED", 1);

    try (var admin =
        DriverManager.getConnection(
            required("CATALOG_MYSQL_URL"), "root", required("MYSQL_BOOTSTRAP_PASSWORD"))) {
      admin.createStatement().execute("SET GLOBAL offline_mode = ON");
      try {
        assertOrderError(
            postOrder(token(), idempotencyKey, intent),
            HttpStatus.SERVICE_UNAVAILABLE,
            "DEPENDENCY_UNAVAILABLE");
      } finally {
        admin.createStatement().execute("SET GLOBAL offline_mode = OFF");
      }
    }

    waitForApplicationDatabase();
    assertThat(postOrder(token(), idempotencyKey, intent).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertOrderCardinality(productId, 4, 1, 1, 1);
    assertTotalIdempotencyCount(13);
  }

  private void waitForApplicationDatabase() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    RuntimeException lastFailure = null;
    while (System.nanoTime() < deadline) {
      try {
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        return;
      } catch (RuntimeException exception) {
        lastFailure = exception;
        Thread.sleep(100);
      }
    }
    throw new AssertionError("Commerce application database pool did not recover", lastFailure);
  }

  private static final class ExhaustionProbeRepository extends OrderRepository {
    private final JdbcTemplate jdbc;
    private final AtomicInteger reads = new AtomicInteger();
    private final CountDownLatch finalResolutionEntered = new CountDownLatch(1);
    private final CountDownLatch continueFinalResolution = new CountDownLatch(1);

    private ExhaustionProbeRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
      super(jdbc, objectMapper);
      this.jdbc = jdbc;
    }

    @Override
    public Optional<IdempotencyRecord> findIdempotencyForUpdate(String user, String key) {
      int read = reads.incrementAndGet();
      if (read == 1) {
        jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
      } else if (read == 2) {
        // With one mutation attempt, only the post-exhaustion truth resolver can issue this read.
        finalResolutionEntered.countDown();
        try {
          if (!continueFinalResolution.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Final committed-truth resolution was not released");
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "Final committed-truth resolution was interrupted", exception);
        }
      }
      return super.findIdempotencyForUpdate(user, key);
    }

    private boolean awaitFinalResolution() throws InterruptedException {
      return finalResolutionEntered.await(10, TimeUnit.SECONDS);
    }

    private void allowFinalResolution() {
      continueFinalResolution.countDown();
    }

    private int finalResolutionReads() {
      return Math.max(0, reads.get() - 1);
    }
  }

  private record ControlledExhaustion(
      OrderResult target,
      OrderException failure,
      ResponseEntity<JsonNode> sibling,
      int finalResolutionReads) {}

  private void seedOrderProduct(
      String productId, long stock, boolean available, String publicationState, long version) {
    jdbc.update(
        """
        INSERT INTO product
          (product_id, name, description, price_minor, currency, stock_quantity,
           available, publication_state, publication_version)
        VALUES (?, ?, 'Order integration product', 750, 'AUD', ?, ?, ?, ?)
        """,
        productId,
        productId + " name",
        stock,
        available,
        publicationState,
        version);
  }

  private void assertOrderCardinality(
      String productId, long stock, int orders, int idempotency, int outbox) {
    assertThat(
            jdbc.queryForObject(
                "SELECT stock_quantity FROM product WHERE product_id = ?", Long.class, productId))
        .isEqualTo(stock);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM standard_order WHERE product_id = ?",
                Integer.class,
                productId))
        .isEqualTo(orders);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM order_idempotency i
                JOIN standard_order o ON o.order_id = i.order_id
                WHERE o.product_id = ?
                """,
                Integer.class,
                productId))
        .isEqualTo(idempotency);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM commerce_outbox x
                JOIN standard_order o ON o.order_id = x.aggregate_id
                WHERE x.aggregate_type = 'STANDARD_ORDER' AND o.product_id = ?
                """,
                Integer.class,
                productId))
        .isEqualTo(outbox);
  }

  private void assertTotalIdempotencyCount(int expected) {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_idempotency", Integer.class))
        .isEqualTo(expected);
  }

  private void assertIdempotencyKeysAbsent(String... keys) {
    for (String key : keys) {
      assertThat(
              jdbc.queryForObject(
                  """
                  SELECT COUNT(*)
                  FROM order_idempotency
                  WHERE user_subject = 'catalog-user' AND idempotency_key = ?
                  """,
                  Integer.class,
                  key))
          .as("rejected key %s must not leave an idempotency guard", key)
          .isZero();
    }
  }

  private static void assertOrderError(
      ResponseEntity<JsonNode> response, HttpStatus status, String category) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getBody().get("category").asText()).isEqualTo(category);
    assertThat(response.getBody().get("correlationId").asText()).isNotBlank();
    assertThat(response.getBody().toString()).doesNotContain("Bearer", "password", "other-user");
  }

  private void seedPublishedAndUnpublishedProducts() {
    jdbc.update(
        """
        INSERT INTO product
          (product_id, name, description, price_minor, currency, stock_quantity,
           available, publication_state, publication_version)
        VALUES (?, 'Visible product', 'Published catalog content', 1000, 'AUD', 8, TRUE,
                'PUBLISHED', 1)
        """,
        VISIBLE_ID);
    jdbc.update(
        """
        INSERT INTO product
          (product_id, name, description, price_minor, currency, stock_quantity,
           available, publication_state, publication_version)
        VALUES (?, 'Hidden product', 'Must not be public', 700, 'AUD', 3, TRUE,
                'UNPUBLISHED', 1)
        """,
        HIDDEN_ID);
    jdbc.update(
        "INSERT INTO catalog_metadata (singleton_id, publication_generation) VALUES (1, 1)");
  }

  private void proveAuthenticatedContractsAndLiveFields() {
    ResponseEntity<JsonNode> list = get("/api/products", token(), null);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(list.getBody()).isNotNull();
    assertThat(list.getBody().isArray()).isTrue();
    assertThat(list.getBody().findValuesAsText("productId"))
        .contains(VISIBLE_ID)
        .doesNotContain(HIDDEN_ID);

    ResponseEntity<JsonNode> first = get("/api/products/" + VISIBLE_ID, token(), null);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(first.getBody().get("priceMinor").asLong()).isEqualTo(1000);

    jdbc.update(
        "UPDATE product SET price_minor = 1250, stock_quantity = 2, available = FALSE WHERE product_id = ?",
        VISIBLE_ID);
    ResponseEntity<JsonNode> live = get("/api/products/" + VISIBLE_ID, token(), null);
    assertThat(live.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(live.getBody().get("priceMinor").asLong()).isEqualTo(1250);
    assertThat(live.getBody().get("stockQuantity").asLong()).isEqualTo(2);
    assertThat(live.getBody().get("available").asBoolean()).isFalse();

    assertThat(get("/api/products/does-not-exist", token(), null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/products/" + HIDDEN_ID, token(), null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/products", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/api/products", "not-a-jwt", null).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/api/products", token(), "forbidden-production-context").getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private void proveRedisProtectionsAndMysqlFallback() throws Exception {
    long generation = repository.catalogGeneration();
    String prefix = "catalog:product:";
    redis.delete(redis.keys("catalog:*") == null ? List.of() : redis.keys("catalog:*"));

    AtomicInteger nullLoads = new AtomicInteger();
    assertThat(
            cache.resolve(
                "bounded-missing",
                generation,
                () -> {
                  nullLoads.incrementAndGet();
                  return Optional.empty();
                }))
        .isEmpty();
    assertThat(
            cache.resolve(
                "bounded-missing",
                generation,
                () -> {
                  nullLoads.incrementAndGet();
                  return Optional.empty();
                }))
        .isEmpty();
    assertThat(nullLoads).hasValue(1);
    Long nullTtl = redis.getExpire("catalog:null:bounded-missing:" + generation, TimeUnit.SECONDS);
    assertThat(nullTtl).isBetween(1L, properties.nullTtl().toSeconds());

    redis.opsForValue().set("catalog:bloom:generation", Long.toString(generation));
    redis.opsForValue().set("catalog:bloom:complete", "false");
    AtomicInteger incompleteLoads = new AtomicInteger();
    assertThat(
            cache.resolve(
                VISIBLE_ID,
                generation,
                () -> {
                  incompleteLoads.incrementAndGet();
                  return repository.findPublished(VISIBLE_ID);
                }))
        .isPresent();
    assertThat(incompleteLoads).hasValue(1);

    cache.rebuildBloom(generation, repository.publishedIds());
    AtomicInteger bloomNegativeLoads = new AtomicInteger();
    assertThat(
            cache.resolve(
                "complete-bloom-missing",
                generation,
                () -> {
                  bloomNegativeLoads.incrementAndGet();
                  return Optional.empty();
                }))
        .isEmpty();
    assertThat(bloomNegativeLoads).hasValue(0);

    redis.delete("catalog:bloom:bits:" + generation);
    AtomicInteger missingBitsLoads = new AtomicInteger();
    assertThat(
            cache.resolve(
                "missing-bitset-product",
                generation,
                () -> {
                  missingBitsLoads.incrementAndGet();
                  return Optional.empty();
                }))
        .isEmpty();
    assertThat(missingBitsLoads).hasValue(1);

    proveConcurrentBloomRebuildCannotManufactureNotFound(generation);

    Product first = new Product("ttl-alpha", "Alpha", "", 1, "AUD", 1, true, 1);
    Product second = new Product("ttl-beta", "Beta", "", 1, "AUD", 1, true, 1);
    cache.put(first, generation);
    cache.put(second, generation);
    Long firstTtl =
        redis.getExpire(prefix + first.productId() + ":" + generation, TimeUnit.SECONDS);
    Long secondTtl =
        redis.getExpire(prefix + second.productId() + ":" + generation, TimeUnit.SECONDS);
    assertThat(firstTtl).isBetween(properties.cacheTtl().toSeconds() - 1, 40L);
    assertThat(secondTtl).isBetween(properties.cacheTtl().toSeconds() - 1, 40L);
    assertThat(firstTtl).isNotEqualTo(secondTtl);

    cache.evict(VISIBLE_ID);
    redis.opsForValue().set("catalog:bloom:complete", "false");
    AtomicInteger concurrentLoads = new AtomicInteger();
    CountDownLatch ready = new CountDownLatch(8);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(8);
    List<java.util.concurrent.Future<Optional<Product>>> futures = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      futures.add(
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return cache.resolve(
                    VISIBLE_ID,
                    generation,
                    () -> {
                      concurrentLoads.incrementAndGet();
                      try {
                        Thread.sleep(150);
                      } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Controlled loader interrupted", exception);
                      }
                      return repository.findPublished(VISIBLE_ID);
                    });
              }));
    }
    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    for (var future : futures) {
      assertThat(future.get(5, TimeUnit.SECONDS)).isPresent();
    }
    executor.shutdownNow();
    assertThat(concurrentLoads).hasValue(1);
    assertThat(redis.opsForValue().get(prefix + VISIBLE_ID + ":" + generation)).isNotBlank();

    Product forged = new Product("redis-forged", "Forged", "", 1, "AUD", 1, true, 1);
    redis
        .opsForValue()
        .set(prefix + forged.productId() + ":" + generation, json(forged), Duration.ofSeconds(30));
    ProductCatalogService service = new ProductCatalogService(repository, cache);
    assertThat(service.findPublished(forged.productId())).isEmpty();
    assertThat(redis.hasKey(prefix + forged.productId() + ":" + generation)).isFalse();

    cache.evict(VISIBLE_ID);
    Product wrongIdentity = new Product("other-product", "Forged", "", 1250, "AUD", 2, false, 1);
    redis
        .opsForValue()
        .set(prefix + VISIBLE_ID + ":" + generation, json(wrongIdentity), Duration.ofSeconds(30));
    Product rebuilt = service.findPublished(VISIBLE_ID).orElseThrow();
    assertThat(rebuilt.productId()).isEqualTo(VISIBLE_ID);
    assertThat(rebuilt.name()).isEqualTo("Visible product");

    try (UnavailableCache unavailable = unavailableCache()) {
      AtomicInteger outageLoads = new AtomicInteger();
      assertThat(
              unavailable
                  .cache()
                  .resolve(
                      VISIBLE_ID,
                      generation,
                      () -> {
                        outageLoads.incrementAndGet();
                        return repository.findPublished(VISIBLE_ID);
                      }))
          .isPresent();
      assertThat(outageLoads).hasValue(1);
    }
  }

  private void proveConcurrentBloomRebuildCannotManufactureNotFound(long generation)
      throws Exception {
    List<String> publishedIds = repository.publishedIds();
    cache.rebuildBloom(generation, publishedIds);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger mysqlLoads = new AtomicInteger();
    AtomicInteger falseNegatives = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    var rebuilds =
        executor.submit(
            () -> {
              ready.countDown();
              start.await();
              for (int iteration = 0; iteration < 200; iteration++) {
                cache.rebuildBloom(generation, publishedIds);
              }
              return null;
            });
    var reads =
        executor.submit(
            () -> {
              ready.countDown();
              start.await();
              for (int iteration = 0; iteration < 400; iteration++) {
                cache.evict(VISIBLE_ID, generation);
                Optional<Product> resolved =
                    cache.resolve(
                        VISIBLE_ID,
                        generation,
                        () -> {
                          mysqlLoads.incrementAndGet();
                          return repository.findPublished(VISIBLE_ID);
                        });
                if (resolved.isEmpty()) {
                  falseNegatives.incrementAndGet();
                }
              }
              return null;
            });
    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    rebuilds.get(20, TimeUnit.SECONDS);
    reads.get(20, TimeUnit.SECONDS);
    executor.shutdownNow();

    assertThat(mysqlLoads).hasPositiveValue();
    assertThat(falseNegatives).hasValue(0);
  }

  private void provePublicationAtomicityAndNormalEventRecovery() throws Exception {
    UUID firstEventId = UUID.randomUUID();
    ProductRepository.ProductDraft firstDraft =
        new ProductRepository.ProductDraft(
            "event-product", "Version one", "", 2100, "AUD", 5, true, true);
    ProductRepository.Publication firstPublication =
        publicationService.publish(firstDraft, firstEventId);
    long generationAfterFirst = repository.catalogGeneration();
    assertThat(repository.findPublished(firstDraft.productId())).isPresent();
    assertThat(outboxState(firstEventId)).isEqualTo("PENDING:0");

    ProductRepository.ProductDraft rolledBack =
        new ProductRepository.ProductDraft(
            firstDraft.productId(), "Must roll back", "", 9999, "AUD", 1, false, true);
    assertThatThrownBy(() -> publicationService.publish(rolledBack, firstEventId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    Product afterRollback = repository.findPublished(firstDraft.productId()).orElseThrow();
    assertThat(afterRollback.name()).isEqualTo("Version one");
    assertThat(afterRollback.publicationVersion()).isEqualTo(1);
    assertThat(repository.catalogGeneration()).isEqualTo(generationAfterFirst);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM commerce_outbox WHERE event_id = ?",
                Integer.class,
                firstEventId.toString()))
        .isEqualTo(1);

    UUID secondEventId = UUID.randomUUID();
    ProductRepository.ProductDraft secondDraft =
        new ProductRepository.ProductDraft(
            firstDraft.productId(), "Version two", "", 2200, "AUD", 6, true, true);
    publicationService.publish(secondDraft, secondEventId);
    assertThat(repository.findPublished(firstDraft.productId()).orElseThrow().publicationVersion())
        .isEqualTo(2);

    Product old = new Product("recovery-product", "Old", "", 3000, "AUD", 4, true, 1);
    jdbc.update(
        """
        INSERT INTO product
          (product_id, name, description, price_minor, currency, stock_quantity,
           available, publication_state, publication_version)
        VALUES (?, ?, '', ?, 'AUD', 4, TRUE, 'PUBLISHED', 1)
        """,
        old.productId(),
        old.name(),
        old.priceMinor());
    cache.put(old, repository.catalogGeneration());
    UUID recoveryEventId = UUID.randomUUID();
    ProductRepository.ProductDraft currentDraft =
        new ProductRepository.ProductDraft(
            old.productId(), "Current", "", 3500, "AUD", 7, true, true);
    try (UnavailableCache unavailable = unavailableCache()) {
      ProductPublicationService deletionFailureService =
          new ProductPublicationService(repository, unavailable.cache());
      transactions.executeWithoutResult(
          status -> deletionFailureService.publish(currentDraft, recoveryEventId));
    }
    Product current = repository.findPublished(old.productId()).orElseThrow();
    assertThat(current.publicationVersion()).isEqualTo(2);
    assertThat(outboxState(recoveryEventId)).isEqualTo("PENDING:0");

    ProductRepository.OutboxEvent failedPublish = repository.pendingOutbox(1).getFirst();
    ProductRepository.OutboxEvent staleEvent = outboxEvent(firstEventId);
    CatalogProperties closedProperties =
        new CatalogProperties(
            properties.issuer(),
            properties.userAudience(),
            properties.jwksUrl(),
            properties.jwksCacheTtl(),
            properties.clockSkew(),
            properties.requiredPermission(),
            properties.cacheTtl(),
            properties.cacheJitter(),
            properties.nullTtl(),
            properties.mutexTtl(),
            properties.rocketmqEndpoints(),
            properties.rocketmqTopic(),
            properties.rocketmqConsumerGroup() + "-closed");
    RocketMqCatalogMessaging closed = new RocketMqCatalogMessaging(closedProperties);
    closed.close();
    assertThatThrownBy(() -> new CatalogOutboxPublisher(repository, closed).publishPending(1))
        .isInstanceOf(Exception.class);
    assertThat(outboxState(UUID.fromString(failedPublish.eventId()))).isEqualTo("PENDING:1");

    int pending = repository.pendingOutbox(100).size();
    assertThat(pending).isGreaterThanOrEqualTo(2);
    assertThat(eventWorker.publishOnce()).isEqualTo(pending);
    assertThat(repository.pendingOutbox(100)).isEmpty();

    var failedDelivery = new java.util.concurrent.atomic.AtomicReference<String>();
    assertThatThrownBy(
            () ->
                messaging.consumeOnce(
                    payload -> {
                      failedDelivery.set(repository.parseEvent(payload).eventId());
                      throw new IllegalStateException("Controlled consumer failure");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Controlled consumer failure");
    assertThat(failedDelivery.get()).isNotBlank();

    ConcurrentHashMap<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
    Thread.sleep(16_000);
    int consumed =
        consumeUntil(
            payload -> {
              String eventId = repository.parseEvent(payload).eventId();
              deliveries.computeIfAbsent(eventId, ignored -> new AtomicInteger()).incrementAndGet();
              invalidationHandler.handle(payload);
            },
            pending,
            Duration.ofSeconds(45));
    assertThat(consumed).isGreaterThanOrEqualTo(pending);
    assertThat(deliveries).containsKey(failedDelivery.get());

    long currentGeneration = repository.catalogGeneration();
    Product cachedCurrent =
        cache.resolve(old.productId(), currentGeneration, Optional::<Product>empty).orElseThrow();
    assertThat(cachedCurrent.name()).isEqualTo("Current");
    assertThat(cachedCurrent.publicationVersion()).isEqualTo(2);

    Product cachedNewer =
        cache
            .resolve(firstDraft.productId(), currentGeneration, Optional::<Product>empty)
            .orElseThrow();
    assertThat(cachedNewer.publicationVersion()).isEqualTo(2);

    messaging.send(staleEvent);
    int beforeDuplicate = deliveries.getOrDefault(staleEvent.eventId(), new AtomicInteger()).get();
    consumeUntil(
        payload -> {
          String eventId = repository.parseEvent(payload).eventId();
          deliveries.computeIfAbsent(eventId, ignored -> new AtomicInteger()).incrementAndGet();
          invalidationHandler.handle(payload);
        },
        1,
        Duration.ofSeconds(30));
    assertThat(deliveries.get(staleEvent.eventId()).get()).isGreaterThan(beforeDuplicate);
    Product afterDuplicate =
        cache
            .resolve(firstDraft.productId(), currentGeneration, Optional::<Product>empty)
            .orElseThrow();
    assertThat(afterDuplicate.publicationVersion()).isEqualTo(2);
    assertThat(firstPublication.event().productVersion()).isEqualTo(1);

    String invalidPayload =
        json(
            new ProductRepository.CatalogEvent(
                UUID.randomUUID().toString(),
                "invalid-product",
                0,
                currentGeneration,
                "PUBLISHED"));
    messaging.send(new ProductRepository.OutboxEvent(UUID.randomUUID().toString(), invalidPayload));
    assertThatThrownBy(eventWorker::consumeOnce).isInstanceOf(IllegalArgumentException.class);
  }

  private int consumeUntil(CatalogEventHandler handler, int expected, Duration timeout)
      throws Exception {
    int consumed = 0;
    long deadline = System.nanoTime() + timeout.toNanos();
    while (consumed < expected && System.nanoTime() < deadline) {
      consumed += messaging.consumeOnce(handler);
    }
    return consumed;
  }

  private void proveProductionConsumerRejectsReservedEvaluationContext() throws Exception {
    String eventId = UUID.randomUUID().toString();
    String payload =
        objectMapper.writeValueAsString(
            Map.of(
                "eventId",
                eventId,
                "productId",
                "production-only-proof",
                "productVersion",
                1,
                "catalogGeneration",
                repository.catalogGeneration(),
                "publicationState",
                "PUBLISHED"));
    ClientConfiguration configuration =
        ClientConfiguration.newBuilder()
            .setEndpoints(required("ROCKETMQ_ENDPOINTS"))
            .setRequestTimeout(Duration.ofSeconds(10))
            .enableSsl(false)
            .build();
    CatalogProperties isolatedProperties =
        new CatalogProperties(
            properties.issuer(),
            properties.userAudience(),
            properties.jwksUrl(),
            properties.jwksCacheTtl(),
            properties.clockSkew(),
            properties.requiredPermission(),
            properties.cacheTtl(),
            properties.cacheJitter(),
            properties.nullTtl(),
            properties.mutexTtl(),
            properties.rocketmqEndpoints(),
            properties.rocketmqTopic(),
            properties.rocketmqConsumerGroup() + "-closed");
    try (RocketMqCatalogMessaging isolated = new RocketMqCatalogMessaging(isolatedProperties);
        Producer producer =
            ClientServiceProvider.loadService()
                .newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(required("ROCKETMQ_TOPIC"))
                .build()) {
      int drained;
      do {
        drained = isolated.consumeOnce(ignored -> assertThat(ignored).isNotNull());
      } while (drained > 0);
      producer.send(
          ClientServiceProvider.loadService()
              .newMessageBuilder()
              .setTopic(required("ROCKETMQ_TOPIC"))
              .setTag("product-publication")
              .setKeys(eventId)
              .addProperty(
                  RocketMqCatalogMessaging.RESERVED_SANDBOX_PROPERTY,
                  "sandbox-must-not-be-accepted")
              .setBody(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .build());

      AtomicInteger handlerCalls = new AtomicInteger();
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (System.nanoTime() < deadline) {
        try {
          isolated.consumeOnce(ignored -> handlerCalls.incrementAndGet());
        } catch (IllegalArgumentException exception) {
          assertThat(exception)
              .hasMessage("Production catalog message cannot carry evaluation context");
          assertThat(handlerCalls).hasValue(0);
          return;
        }
      }
    }
    throw new AssertionError("Reserved evaluation context was not delivered by the real Broker");
  }

  private String outboxState(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT CONCAT(publication_state, ':', publish_attempts) FROM commerce_outbox WHERE event_id = ?",
        String.class,
        eventId.toString());
  }

  private ProductRepository.OutboxEvent outboxEvent(UUID eventId) {
    String payload =
        jdbc.queryForObject(
            "SELECT payload FROM commerce_outbox WHERE event_id = ?",
            String.class,
            eventId.toString());
    return new ProductRepository.OutboxEvent(eventId.toString(), payload);
  }

  private ResponseEntity<JsonNode> get(String path, String bearer, String evalSandbox) {
    HttpHeaders headers = new HttpHeaders();
    if (bearer != null) {
      headers.setBearerAuth(bearer);
    }
    if (evalSandbox != null) {
      headers.set("X-Eval-Sandbox-Id", evalSandbox);
    }
    return rest.exchange(
        "http://127.0.0.1:" + port + path,
        HttpMethod.GET,
        new HttpEntity<>(headers),
        JsonNode.class);
  }

  private ResponseEntity<JsonNode> postOrder(String bearer, String idempotencyKey, Object body) {
    HttpHeaders headers = new HttpHeaders();
    if (bearer != null) {
      headers.setBearerAuth(bearer);
    }
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    headers.set("X-Correlation-Id", "cb040-integration");
    return rest.exchange(
        "http://127.0.0.1:" + port + "/api/orders",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        JsonNode.class);
  }

  private UnavailableCache unavailableCache() {
    RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration("127.0.0.1", 1);
    LettuceClientConfiguration client =
        LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(250))
            .shutdownTimeout(Duration.ZERO)
            .build();
    LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, client);
    factory.afterPropertiesSet();
    factory.start();
    StringRedisTemplate template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    return new UnavailableCache(new ProductCache(template, objectMapper, properties), factory);
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String token() {
    return required("CATALOG_DIRECT_TOKEN");
  }

  private static String otherToken() {
    return required("CATALOG_OTHER_DIRECT_TOKEN");
  }

  private static String limitedToken() {
    return required("CATALOG_LIMITED_DIRECT_TOKEN");
  }

  private static String signedTestToken(String issuer, String audience, String tokenType)
      throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject("catalog-user")
            .claim("token_type", tokenType)
            .claim("principal_state", "ACTIVE")
            .claim("permissions", List.of("order:create"))
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString())
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("catalog-current").build(), claims);
    jwt.sign(new RSASSASigner(testSigningPrivateKey()));
    return jwt.serialize();
  }

  private static RSAPrivateKey testSigningPrivateKey() throws Exception {
    String pem = Files.readString(Path.of(required("CATALOG_TEST_SIGNING_PRIVATE_KEY_PATH")));
    String encoded =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    return (RSAPrivateKey)
        KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }

  private record UnavailableCache(ProductCache cache, LettuceConnectionFactory factory)
      implements AutoCloseable {
    @Override
    public void close() {
      factory.destroy();
    }
  }
}
