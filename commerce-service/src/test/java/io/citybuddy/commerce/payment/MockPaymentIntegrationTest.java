package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.citybuddy.commerce.seckill.SeckillActivityRepository;
import io.citybuddy.commerce.seckill.SeckillCancellationService;
import io.citybuddy.commerce.seckill.SeckillOrderRepository;
import io.citybuddy.commerce.seckill.SeckillReservationRepository;
import io.citybuddy.commerce.seckill.SeckillTimeoutMessage;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockPaymentIntegrationTest {
  private static final String USER = "catalog-user";
  private static final String OTHER_USER = "other-user";
  private static final String CALLBACK_KEY = "integration-key";
  private static final String CALLBACK_SECRET = "not-a-secret-not-a-secret-not-a-secret";

  @DynamicPropertySource
  static void integrationProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("spring.data.redis.url", () -> required("CATALOG_REDIS_URL"));
    registry.add("citybuddy.catalog.enabled", () -> "true");
    registry.add("citybuddy.catalog.issuer", () -> "https://identity.citybuddy.test");
    registry.add("citybuddy.catalog.user-audience", () -> "citybuddy-web");
    registry.add("citybuddy.catalog.jwks-url", () -> required("IDENTITY_JWKS_URL"));
    registry.add("citybuddy.catalog.jwks-cache-ttl", () -> "30s");
    registry.add("citybuddy.catalog.clock-skew", () -> "30s");
    registry.add("citybuddy.catalog.required-permission", () -> "catalog:read");
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
    registry.add("citybuddy.mock-payment.enabled", () -> "true");
    registry.add("citybuddy.mock-payment.required-permission", () -> "payment:create");
    registry.add(
        "citybuddy.mock-payment.callback-key-id", () -> required("MOCK_PAYMENT_CALLBACK_KEY_ID"));
    registry.add(
        "citybuddy.mock-payment.callback-secret", () -> required("MOCK_PAYMENT_CALLBACK_SECRET"));
    registry.add("citybuddy.mock-payment.callback-maximum-age", () -> "5m");
    registry.add("citybuddy.mock-payment.callback-clock-skew", () -> "30s");
  }

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MockPaymentService payments;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void directApiAndSignedCallbackKeepOneAuthoritativePaidTruth() {
    String orderId = seedStandardOrder(USER, 1500);

    assertThat(start(null, orderId, "payment-no-token", body(1500)).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(start(limitedToken(), orderId, "payment-limited", body(1500)).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(start(otherToken(), orderId, "payment-other", body(1500)).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            start(
                    directToken(),
                    orderId,
                    "payment-body-owner",
                    Map.of("amountMinor", 1500, "currency", "AUD", "userSubject", OTHER_USER))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(start(directToken(), orderId, "payment-wrong-amount", body(1499)).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);

    ResponseEntity<MockPaymentResult> created =
        start(directToken(), orderId, "payment-standard", body(1500));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    MockPaymentResult attempt = created.getBody();
    assertThat(attempt).isNotNull();
    assertThat(attempt.state()).isEqualTo("PENDING");
    assertThat(attempt.replayed()).isFalse();

    ResponseEntity<MockPaymentResult> replay =
        start(directToken(), orderId, "payment-standard", body(1500));
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getBody()).isNotNull();
    assertThat(replay.getBody().attemptId()).isEqualTo(attempt.attemptId());
    assertThat(replay.getBody().callbackCorrelationId()).isEqualTo(attempt.callbackCorrelationId());
    assertThat(start(directToken(), orderId, "payment-standard", body(1501)).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(start(directToken(), orderId, "payment-second-attempt", body(1500)).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);

    MockPaymentCallbackRequest callback = callback(attempt, UUID.randomUUID().toString());
    long attemptsBefore = count("mock_payment_attempt");
    long callbacksBefore = count("mock_payment_callback");
    assertThat(callback(callback, "callback-unsigned", null, null).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            callback(
                    callback,
                    "callback-stale",
                    Instant.now().minus(6, ChronoUnit.MINUTES),
                    CALLBACK_SECRET)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            callback(callback, "callback-wrong-secret", Instant.now(), "x".repeat(32))
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(count("mock_payment_attempt")).isEqualTo(attemptsBefore);
    assertThat(count("mock_payment_callback")).isEqualTo(callbacksBefore);

    ResponseEntity<MockPaymentCallbackResult> applied =
        callback(callback, "callback-standard", Instant.now(), CALLBACK_SECRET);
    assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(applied.getBody()).isNotNull();
    assertThat(applied.getBody().state()).isEqualTo("SUCCEEDED");
    assertThat(applied.getBody().replayed()).isFalse();
    assertPaidTruth(orderId, attempt.attemptId(), "STANDARD_PAYMENT", 1500);

    ResponseEntity<MockPaymentCallbackResult> duplicate =
        callback(callback, "callback-standard", Instant.now(), CALLBACK_SECRET);
    assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(duplicate.getBody()).isNotNull();
    assertThat(duplicate.getBody().replayed()).isTrue();
    MockPaymentCallbackRequest reordered = callback(attempt, UUID.randomUUID().toString());
    ResponseEntity<MockPaymentCallbackResult> reorderedResult =
        callback(reordered, "callback-reordered", Instant.now(), CALLBACK_SECRET);
    assertThat(reorderedResult.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(reorderedResult.getBody()).isNotNull();
    assertThat(reorderedResult.getBody().replayed()).isTrue();
    assertPaidTruth(orderId, attempt.attemptId(), "STANDARD_PAYMENT", 1500);
  }

  @Test
  void callbackTransactionRollsBackEveryPartialWriteAndRetryConverges() {
    String orderId = seedStandardOrder(USER, 2200);
    MockPaymentResult attempt =
        payments.start(
            USER, orderId, "payment-rollback", new MockPaymentRequest(2200L, "AUD", null));
    MockPaymentCallbackRequest callback = callback(attempt, UUID.randomUUID().toString());
    long callbacksBefore = count("mock_payment_callback");

    MockPaymentRepository failingRepository =
        new MockPaymentRepository(jdbc) {
          @Override
          public void insertCallback(CallbackRecord record) {
            throw new DataAccessResourceFailureException("injected callback persistence failure");
          }
        };
    MockPaymentService failing =
        new MockPaymentService(failingRepository, transactionTemplate(), Clock.systemUTC());

    assertThatThrownBy(() -> failing.callback("callback-rollback", callback))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(orderState(orderId)).containsExactly("UNPAID", "1");
    assertThat(attemptState(attempt.attemptId())).containsExactly("PENDING", "1");
    assertThat(paymentMovementCount(attempt.attemptId())).isZero();
    assertThat(count("mock_payment_callback")).isEqualTo(callbacksBefore);

    MockPaymentCallbackResult recovered = payments.callback("callback-rollback", callback);
    assertThat(recovered.state()).isEqualTo("SUCCEEDED");
    assertPaidTruth(orderId, attempt.attemptId(), "STANDARD_PAYMENT", 2200);
  }

  @Test
  void concurrentDuplicateCallbackCommitsExactlyOnce() throws Exception {
    for (int iteration = 0; iteration < 20; iteration++) {
      long amount = 3300L + iteration;
      String orderId = seedStandardOrder(USER, amount);
      String requestKey = "payment-concurrent-" + iteration;
      String callbackKey = "callback-concurrent-" + iteration;
      MockPaymentResult attempt =
          payments.start(USER, orderId, requestKey, new MockPaymentRequest(amount, "AUD", null));
      MockPaymentCallbackRequest callback = callback(attempt, UUID.randomUUID().toString());
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch release = new CountDownLatch(1);

      CompletableFuture<MockPaymentCallbackResult> first =
          CompletableFuture.supplyAsync(
              () -> concurrentCallback(ready, release, callbackKey, callback));
      CompletableFuture<MockPaymentCallbackResult> second =
          CompletableFuture.supplyAsync(
              () -> concurrentCallback(ready, release, callbackKey, callback));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      release.countDown();

      assertThat(first.get(10, TimeUnit.SECONDS).replayed())
          .isNotEqualTo(second.get(10, TimeUnit.SECONDS).replayed());
      assertPaidTruth(orderId, attempt.attemptId(), "STANDARD_PAYMENT", amount);
    }
  }

  @Test
  void exactMysqlDeadlockRetriesOnceAndOtherLockFailuresRemainVisible() {
    String orderId = seedStandardOrder(USER, 3500);
    MockPaymentResult attempt =
        payments.start(
            USER, orderId, "payment-deadlock", new MockPaymentRequest(3500L, "AUD", null));
    MockPaymentCallbackRequest callback = callback(attempt, UUID.randomUUID().toString());
    payments.callback("callback-deadlock", callback);

    AtomicInteger deadlockCalls = new AtomicInteger();
    MockPaymentRepository oneDeadlock =
        new MockPaymentRepository(jdbc) {
          @Override
          public java.util.Optional<AttemptRecord> findAttemptByCorrelationForUpdate(
              String correlationId) {
            if (deadlockCalls.incrementAndGet() == 1) {
              throw lockFailure(1213);
            }
            return super.findAttemptByCorrelationForUpdate(correlationId);
          }
        };
    MockPaymentService retrying =
        new MockPaymentService(oneDeadlock, transactionTemplate(), Clock.systemUTC());

    MockPaymentCallbackResult converged = retrying.callback("callback-deadlock", callback);
    assertThat(converged.replayed()).isTrue();
    assertThat(deadlockCalls).hasValue(2);
    assertPaidTruth(orderId, attempt.attemptId(), "STANDARD_PAYMENT", 3500);
    assertThat(paymentMovementCount(attempt.attemptId())).isOne();

    AtomicInteger timeoutCalls = new AtomicInteger();
    MockPaymentRepository lockTimeout =
        new MockPaymentRepository(jdbc) {
          @Override
          public java.util.Optional<AttemptRecord> findAttemptByCorrelationForUpdate(
              String correlationId) {
            timeoutCalls.incrementAndGet();
            throw lockFailure(1205);
          }
        };
    MockPaymentService nonRetrying =
        new MockPaymentService(lockTimeout, transactionTemplate(), Clock.systemUTC());

    assertThatThrownBy(() -> nonRetrying.callback("callback-deadlock", callback))
        .isInstanceOf(CannotAcquireLockException.class);
    assertThat(timeoutCalls).hasValue(1);
    assertThat(paymentMovementCount(attempt.attemptId())).isOne();
  }

  @Test
  void paymentAndCurrentUnpaidCancellationRaceToOneFinalState() throws Exception {
    SeckillFixture payFirst = seedSeckillOrder("pay-first", 4100);
    MockPaymentResult payFirstAttempt =
        payments.start(
            USER,
            payFirst.orderId(),
            "payment-seckill-pay-first",
            new MockPaymentRequest(4100L, "AUD", null));
    payments.callback(
        "callback-seckill-pay-first", callback(payFirstAttempt, UUID.randomUUID().toString()));
    assertThat(cancellations().cancel(payFirst.timeout()).outcome())
        .isEqualTo(SeckillCancellationService.Outcome.FINAL_PRESERVED);
    assertThat(seckillStatus(payFirst.orderId())).isEqualTo("PAID");
    assertThat(movementCount(payFirst.orderId(), "SECKILL_UNPAID_CANCEL")).isZero();
    assertThat(movementCount(payFirst.orderId(), "SECKILL_PAYMENT")).isOne();

    SeckillFixture cancelFirst = seedSeckillOrder("cancel-first", 4200);
    MockPaymentResult cancelFirstAttempt =
        payments.start(
            USER,
            cancelFirst.orderId(),
            "payment-seckill-cancel-first",
            new MockPaymentRequest(4200L, "AUD", null));
    assertThat(cancellations().cancel(cancelFirst.timeout()).outcome())
        .isEqualTo(SeckillCancellationService.Outcome.CANCELLED);
    assertThatThrownBy(
            () ->
                payments.callback(
                    "callback-seckill-cancel-first",
                    callback(cancelFirstAttempt, UUID.randomUUID().toString())))
        .isInstanceOfSatisfying(
            MockPaymentException.class, exception -> assertThat(exception.status()).isEqualTo(409));
    assertThat(seckillStatus(cancelFirst.orderId())).isEqualTo("CANCELLED");
    assertThat(movementCount(cancelFirst.orderId(), "SECKILL_UNPAID_CANCEL")).isOne();
    assertThat(movementCount(cancelFirst.orderId(), "SECKILL_PAYMENT")).isZero();

    SeckillFixture racing = seedSeckillOrder("racing", 4300);
    MockPaymentResult racingAttempt =
        payments.start(
            USER,
            racing.orderId(),
            "payment-seckill-racing",
            new MockPaymentRequest(4300L, "AUD", null));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Object> payment =
        CompletableFuture.supplyAsync(
            () -> {
              awaitRace(ready, release);
              try {
                return payments.callback(
                    "callback-seckill-racing",
                    callback(racingAttempt, UUID.randomUUID().toString()));
              } catch (MockPaymentException exception) {
                return exception;
              }
            });
    CompletableFuture<SeckillCancellationService.CancellationResult> cancellation =
        CompletableFuture.supplyAsync(
            () -> {
              awaitRace(ready, release);
              return cancellations().cancel(racing.timeout());
            });
    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    release.countDown();
    Object paymentOutcome = payment.get(10, TimeUnit.SECONDS);
    SeckillCancellationService.CancellationResult cancellationOutcome =
        cancellation.get(10, TimeUnit.SECONDS);

    String finalState = seckillStatus(racing.orderId());
    if ("PAID".equals(finalState)) {
      assertThat(paymentOutcome).isInstanceOf(MockPaymentCallbackResult.class);
      assertThat(cancellationOutcome.outcome())
          .isEqualTo(SeckillCancellationService.Outcome.FINAL_PRESERVED);
      assertThat(movementCount(racing.orderId(), "SECKILL_PAYMENT")).isOne();
      assertThat(movementCount(racing.orderId(), "SECKILL_UNPAID_CANCEL")).isZero();
    } else {
      assertThat(finalState).isEqualTo("CANCELLED");
      assertThat(paymentOutcome).isInstanceOf(MockPaymentException.class);
      assertThat(movementCount(racing.orderId(), "SECKILL_PAYMENT")).isZero();
      assertThat(movementCount(racing.orderId(), "SECKILL_UNPAID_CANCEL")).isOne();
    }
  }

  private ResponseEntity<MockPaymentResult> start(
      String token, String orderId, String idempotencyKey, Map<String, Object> request) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    headers.set("Idempotency-Key", idempotencyKey);
    return http.exchange(
        "/api/orders/" + orderId + "/mock-payment",
        HttpMethod.POST,
        new HttpEntity<>(request, headers),
        MockPaymentResult.class);
  }

  private ResponseEntity<MockPaymentCallbackResult> callback(
      MockPaymentCallbackRequest request, String idempotencyKey, Instant signedAt, String secret) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("Idempotency-Key", idempotencyKey);
    if (signedAt != null && secret != null) {
      String timestamp = Long.toString(signedAt.getEpochSecond());
      headers.set("X-Mock-Payment-Key-Id", CALLBACK_KEY);
      headers.set("X-Mock-Payment-Timestamp", timestamp);
      String canonical =
          MockPaymentCallbackAuthenticator.canonical(
              CALLBACK_KEY, timestamp, idempotencyKey, request);
      headers.set(
          "X-Mock-Payment-Signature",
          HexFormat.of().formatHex(MockPaymentCallbackAuthenticator.hmac(secret, canonical)));
    }
    return http.exchange(
        "/internal/mock-payments/callback",
        HttpMethod.POST,
        new HttpEntity<>(request, headers),
        MockPaymentCallbackResult.class);
  }

  private String seedStandardOrder(String user, long amount) {
    String orderId = UUID.randomUUID().toString();
    jdbc.update(
        """
        INSERT INTO standard_order
          (order_id, user_subject, product_id, product_name, unit_price_minor, currency,
           quantity, total_price_minor, product_version)
        VALUES (?, ?, ?, 'Payment fixture', ?, 'AUD', 1, ?, 1)
        """,
        orderId,
        user,
        "payment-product-" + orderId.substring(0, 8),
        amount,
        amount);
    return orderId;
  }

  private SeckillFixture seedSeckillOrder(String suffix, long amount) {
    String orderId = UUID.randomUUID().toString();
    String reservationId = UUID.randomUUID().toString();
    String transactionId = UUID.randomUUID().toString();
    String timeoutId = UUID.randomUUID().toString();
    String productId = "payment-product-" + suffix;
    String activityId = "payment-activity-" + suffix;
    Instant deadline = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
    jdbc.update(
        """
        INSERT INTO product
          (product_id, name, description, price_minor, currency, stock_quantity, available,
           publication_state, publication_version)
        VALUES (?, 'Payment fixture', '', ?, 'AUD', 9, TRUE, 'PUBLISHED', 1)
        """,
        productId,
        amount);
    jdbc.update(
        """
        INSERT INTO seckill_activity
          (activity_id, product_id, starts_at, ends_at, state, allocated_quota,
           projection_version)
        VALUES (?, ?, TIMESTAMPADD(HOUR, -1, CURRENT_TIMESTAMP(6)),
                TIMESTAMPADD(HOUR, 1, CURRENT_TIMESTAMP(6)), 'ACTIVE', 10, 1)
        """,
        activityId,
        productId);
    jdbc.update(
        """
        INSERT INTO seckill_reservation
          (reservation_id, user_subject, activity_id, idempotency_key, intent_hash, quantity,
           activity_projection_version, state, decision_code, projection_version, order_id,
           transaction_resolution_due_at)
        VALUES (?, ?, ?, ?, REPEAT('0', 64), 1, 1, 'ORDERED', 'ADMITTED', 3, ?, ?)
        """,
        reservationId,
        USER,
        activityId,
        "reservation-" + suffix,
        orderId,
        java.sql.Timestamp.from(deadline));
    jdbc.update(
        """
        INSERT INTO seckill_order
          (order_id, reservation_id, transaction_event_id, timeout_event_id, user_subject,
           activity_id, product_id, product_name, unit_price_minor, currency, quantity,
           total_price_minor, unpaid_deadline, timeout_dispatch_state,
           timeout_broker_message_id, timeout_dispatched_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'Payment fixture', ?, 'AUD', 1, ?, ?, 'SENT',
                'fixture-dispatched', CURRENT_TIMESTAMP(6))
        """,
        orderId,
        reservationId,
        transactionId,
        timeoutId,
        USER,
        activityId,
        productId,
        amount,
        amount,
        java.sql.Timestamp.from(deadline));
    jdbc.update(
        """
        INSERT INTO inventory_ledger
          (movement_id, business_event_key, movement_type, order_id, reservation_id, activity_id,
           product_id, inventory_delta, activity_quota_delta)
        VALUES (?, ?, 'SECKILL_ORDER_CREATE', ?, ?, ?, ?, -1, -1)
        """,
        UUID.randomUUID().toString(),
        "payment-order-create:" + transactionId,
        orderId,
        reservationId,
        activityId,
        productId);
    return new SeckillFixture(
        orderId,
        new SeckillTimeoutMessage(
            timeoutId, orderId, reservationId, "UNPAID", 1, deadline, transactionId));
  }

  private SeckillCancellationService cancellations() {
    return new SeckillCancellationService(
        new SeckillOrderRepository(jdbc),
        new SeckillReservationRepository(jdbc),
        new SeckillActivityRepository(jdbc),
        (activity, targetVersion, quantity) -> {},
        transactionTemplate(),
        Clock.systemUTC());
  }

  private TransactionTemplate transactionTemplate() {
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transactions;
  }

  private MockPaymentCallbackResult concurrentCallback(
      CountDownLatch ready,
      CountDownLatch release,
      String idempotencyKey,
      MockPaymentCallbackRequest callback) {
    awaitRace(ready, release);
    return payments.callback(idempotencyKey, callback);
  }

  private static void awaitRace(CountDownLatch ready, CountDownLatch release) {
    ready.countDown();
    try {
      if (!release.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Payment race was not released");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Payment race was interrupted", exception);
    }
  }

  private static CannotAcquireLockException lockFailure(int errorCode) {
    return new CannotAcquireLockException(
        "controlled MySQL lock failure",
        new SQLException("controlled MySQL lock failure", "40001", errorCode));
  }

  private void assertPaidTruth(
      String orderId, String attemptId, String movementType, long amountMinor) {
    assertThat(orderState(orderId)).containsExactly("PAID", "2");
    assertThat(attemptState(attemptId)).containsExactly("SUCCEEDED", "2");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM mock_payment_callback WHERE attempt_id = ?",
                Long.class,
                attemptId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM inventory_ledger
                WHERE business_event_key = ? AND movement_type = ?
                  AND payment_amount_minor = ? AND payment_currency = 'AUD'
                  AND inventory_delta = 0 AND activity_quota_delta = 0
                """,
                Long.class,
                "mock-payment:" + attemptId,
                movementType,
                amountMinor))
        .isOne();
  }

  private java.util.List<String> orderState(String orderId) {
    java.util.List<String> standard =
        jdbc
            .query(
                "SELECT status, state_version FROM standard_order WHERE order_id = ?",
                (row, index) -> java.util.List.of(row.getString(1), row.getString(2)),
                orderId)
            .stream()
            .findFirst()
            .orElse(null);
    if (standard != null) {
      return standard;
    }
    return jdbc
        .query(
            "SELECT status, state_version FROM seckill_order WHERE order_id = ?",
            (row, index) -> java.util.List.of(row.getString(1), row.getString(2)),
            orderId)
        .stream()
        .findFirst()
        .orElseThrow();
  }

  private java.util.List<String> attemptState(String attemptId) {
    return jdbc.queryForObject(
        "SELECT state, state_version FROM mock_payment_attempt WHERE attempt_id = ?",
        (row, index) -> java.util.List.of(row.getString(1), row.getString(2)),
        attemptId);
  }

  private long paymentMovementCount(String attemptId) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE business_event_key = ?",
            Long.class,
            "mock-payment:" + attemptId);
    return count == null ? 0 : count;
  }

  private long movementCount(String orderId, String movementType) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE order_id = ? AND movement_type = ?",
            Long.class,
            orderId,
            movementType);
    return count == null ? 0 : count;
  }

  private String seckillStatus(String orderId) {
    return jdbc.queryForObject(
        "SELECT status FROM seckill_order WHERE order_id = ?", String.class, orderId);
  }

  private long count(String table) {
    if (!java.util.Set.of(
            "mock_payment_attempt", "mock_payment_callback", "standard_order", "inventory_ledger")
        .contains(table)) {
      throw new IllegalArgumentException("Unexpected count table");
    }
    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    return count == null ? 0 : count;
  }

  private static Map<String, Object> body(long amountMinor) {
    return Map.of("amountMinor", amountMinor, "currency", "AUD");
  }

  private static MockPaymentCallbackRequest callback(
      MockPaymentResult attempt, String callbackEventId) {
    return new MockPaymentCallbackRequest(
        callbackEventId,
        attempt.callbackCorrelationId(),
        attempt.orderId(),
        attempt.amountMinor(),
        attempt.currency(),
        "SUCCEEDED");
  }

  private static String directToken() {
    return required("CATALOG_DIRECT_TOKEN");
  }

  private static String otherToken() {
    return required("CATALOG_OTHER_DIRECT_TOKEN");
  }

  private static String limitedToken() {
    return required("CATALOG_LIMITED_DIRECT_TOKEN");
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }

  private record SeckillFixture(String orderId, SeckillTimeoutMessage timeout) {}
}
