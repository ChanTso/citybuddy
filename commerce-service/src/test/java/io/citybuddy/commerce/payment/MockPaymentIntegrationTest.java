package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.citybuddy.commerce.evaluation.EvaluationSandboxException;
import io.citybuddy.commerce.evaluation.EvaluationSandboxRepository;
import io.citybuddy.commerce.evaluation.EvaluationViewRepository;
import io.citybuddy.commerce.seckill.SeckillActivityRepository;
import io.citybuddy.commerce.seckill.SeckillCancellationService;
import io.citybuddy.commerce.seckill.SeckillOrderRepository;
import io.citybuddy.commerce.seckill.SeckillReservationRepository;
import io.citybuddy.commerce.seckill.SeckillTimeoutMessage;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    assertThat(
            start(
                    directToken(),
                    orderId,
                    "payment-extra-field",
                    Map.of("amountMinor", 1500, "currency", "AUD", "metadata", "forbidden"))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            start(
                    directToken(),
                    "production-cannot-add-evaluation",
                    orderId,
                    "payment-evaluation-header",
                    body(1500))
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
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
    MockPaymentCallbackRequest evaluationContext =
        new MockPaymentCallbackRequest(
            UUID.randomUUID().toString(),
            attempt.callbackCorrelationId(),
            attempt.orderId(),
            attempt.amountMinor(),
            attempt.currency(),
            "SUCCEEDED",
            "production-cannot-add-evaluation",
            "payment-session",
            "payment-trace",
            "a".repeat(64));
    assertThat(
            callback(
                    evaluationContext,
                    "callback-production-evaluation",
                    Instant.now(),
                    CALLBACK_SECRET)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
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
  void committedReplayRejectsCallbackIdentityOwnedByAnotherPayment() {
    String firstOrderId = seedStandardOrder(USER, 1510);
    String secondOrderId = seedStandardOrder(USER, 1520);
    MockPaymentResult first =
        start(directToken(), firstOrderId, "payment-callback-owner-a", body(1510)).getBody();
    MockPaymentResult second =
        start(directToken(), secondOrderId, "payment-callback-owner-b", body(1520)).getBody();
    assertThat(first).isNotNull();
    assertThat(second).isNotNull();

    String firstEventId = UUID.randomUUID().toString();
    String secondEventId = UUID.randomUUID().toString();
    String firstCallbackKey = "callback-owner-a";
    String secondCallbackKey = "callback-owner-b";
    assertThat(
            callback(
                    callback(first, firstEventId), firstCallbackKey, Instant.now(), CALLBACK_SECRET)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            callback(
                    callback(second, secondEventId),
                    secondCallbackKey,
                    Instant.now(),
                    CALLBACK_SECRET)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    long callbacksBefore = count("mock_payment_callback");
    long movementsBefore = count("inventory_ledger");

    MockPaymentCallbackRequest foreignKeyReplay = callback(first, UUID.randomUUID().toString());
    assertThat(
            callback(foreignKeyReplay, secondCallbackKey, Instant.now(), CALLBACK_SECRET)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);

    MockPaymentCallbackRequest foreignEventReplay = callback(first, secondEventId);
    assertThat(
            callback(foreignEventReplay, "callback-owner-a-new-key", Instant.now(), CALLBACK_SECRET)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(count("mock_payment_callback")).isEqualTo(callbacksBefore);
    assertThat(count("inventory_ledger")).isEqualTo(movementsBefore);
    assertPaidTruth(firstOrderId, first.attemptId(), "STANDARD_PAYMENT", 1510);
    assertPaidTruth(secondOrderId, second.attemptId(), "STANDARD_PAYMENT", 1520);
  }

  @Test
  void productionStartMapsCrossTypeOrderCardinalityDamageToConflict() {
    String standardOrderId = seedStandardOrder(USER, 1600);
    SeckillFixture sibling =
        seedSeckillOrder("ambiguous-" + UUID.randomUUID().toString().substring(0, 8), 1600);
    assertThat(
            jdbc.update(
                "UPDATE seckill_order SET order_id = ? WHERE order_id = ?",
                standardOrderId,
                sibling.orderId()))
        .isOne();
    long attemptsBefore = count("mock_payment_attempt");

    try {
      ResponseEntity<String> response =
          startRaw(directToken(), standardOrderId, "payment-ambiguous-order", body(1600));

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(response.getBody()).contains("\"category\":\"CONFLICT\"");
      assertThat(count("mock_payment_attempt")).isEqualTo(attemptsBefore);
    } finally {
      assertThat(
              jdbc.update(
                  "UPDATE seckill_order SET order_id = ? WHERE order_id = ?",
                  sibling.orderId(),
                  standardOrderId))
          .isOne();
    }
  }

  @Test
  void paymentJsonRejectionClosesUnknownAndMalformedInputClasses() {
    String orderId = seedStandardOrder(USER, 1700);
    List<Map<String, Object>> startBodies =
        List.of(
            mapWith("amountMinor", 1700, "currency", "AUD", "metadata", null),
            mapWith("amountMinor", 1700, "currency", "AUD", "metadata", Map.of("x", 1)),
            mapWith("amountMinor", 1700, "currency", "AUD", "metadata", List.of("x")),
            mapWith("amountMinor", Map.of("value", 1700), "currency", "AUD"),
            mapWith("amountMinor", 1700, "currency", List.of("AUD")));
    String startFailure = null;
    for (int index = 0; index < startBodies.size(); index++) {
      ResponseEntity<String> response =
          startRaw(directToken(), orderId, "payment-invalid-json-" + index, startBodies.get(index));
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      if (startFailure == null) {
        startFailure = response.getBody();
      } else {
        assertThat(response.getBody()).isEqualTo(startFailure);
      }
    }

    MockPaymentResult attempt =
        payments.start(
            USER, orderId, "payment-valid-after-json", new MockPaymentRequest(1700L, "AUD", null));
    MockPaymentCallbackRequest valid = callback(attempt, UUID.randomUUID().toString());
    Map<String, Object> base = callbackBody(valid);
    List<Map<String, Object>> callbackBodies =
        List.of(
            withExtra(base, "metadata", null),
            withExtra(base, "metadata", Map.of("nested", true)),
            withExtra(base, "metadata", List.of("nested")),
            withExtra(base, "amountMinor", Map.of("value", 1700)),
            withExtra(base, "currency", List.of("AUD")));
    String callbackFailure = null;
    for (int index = 0; index < callbackBodies.size(); index++) {
      ResponseEntity<String> response =
          callbackRaw(valid, "callback-invalid-json-" + index, callbackBodies.get(index));
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      if (callbackFailure == null) {
        callbackFailure = response.getBody();
      } else {
        assertThat(response.getBody()).isEqualTo(callbackFailure);
      }
    }
    assertThat(orderState(orderId)).containsExactly("UNPAID", "1");
    assertThat(callbackCount(attempt.attemptId())).isZero();
    assertThat(paymentMovementCount(attempt.attemptId())).isZero();
  }

  @Test
  void callbackParsingFailuresConvergeAtOnePublicBoundary() {
    List<byte[]> malformedBodies =
        List.of(
            "{".getBytes(StandardCharsets.UTF_8),
            "[]".getBytes(StandardCharsets.UTF_8),
            "{\"amountMinor\":{}}".getBytes(StandardCharsets.UTF_8),
            "{\"amountMinor\":999999999999999999999999999999}".getBytes(StandardCharsets.UTF_8),
            "{\"callbackEventId\":\"a\u0001b\"}".getBytes(StandardCharsets.UTF_8),
            new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xc3, 0x28, '"', '}'});

    String expected = null;
    for (int index = 0; index < malformedBodies.size(); index++) {
      ResponseEntity<String> response =
          callbackRawPayload("callback-parse-failure-" + index, malformedBodies.get(index));
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      if (expected == null) {
        expected = response.getBody();
      } else {
        assertThat(response.getBody()).isEqualTo(expected);
      }
    }
    assertThat(expected).contains("\"category\":\"VALIDATION\"");
    assertThat(expected).contains("\"message\":\"Payment callback is invalid\"");

    String orderId = seedStandardOrder(USER, 1701);
    MockPaymentResult attempt =
        payments.start(
            USER,
            orderId,
            "payment-non-ascii-callback",
            new MockPaymentRequest(1701L, "AUD", null));
    MockPaymentCallbackRequest nonAscii = callback(attempt, UUID.randomUUID().toString());
    nonAscii.setCurrency("澳元");
    ResponseEntity<String> decoded =
        callbackRaw(nonAscii, "callback-non-ascii", callbackBody(nonAscii));
    assertThat(decoded.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(decoded.getBody()).isEqualTo(expected);
    assertThat(attemptState(attempt.attemptId())).containsExactly("PENDING", "1");
  }

  @Test
  void evaluationLookupsConcealOtherActiveSandboxAndReplayRequiresExactFullIntent() {
    EvaluationPaymentFixture first = seedEvaluationPayment("full-intent-first");
    EvaluationPaymentFixture second = seedEvaluationPayment("full-intent-second");
    MockPaymentService evaluation = evaluationPayments(new MockPaymentRepository(jdbc));

    String crossOrder =
        paymentFailure(
            () ->
                evaluation.start(
                    second.userSubject(),
                    second.sandboxId(),
                    first.orderId(),
                    "payment-cross-order",
                    new MockPaymentRequest(1800L, "CNY", null)));
    String unknownOrder =
        paymentFailure(
            () ->
                evaluation.start(
                    second.userSubject(),
                    second.sandboxId(),
                    UUID.randomUUID().toString(),
                    "payment-unknown-order",
                    new MockPaymentRequest(1800L, "CNY", null)));
    assertThat(crossOrder).isEqualTo(unknownOrder);

    MockPaymentResult attempt =
        evaluation.start(
            first.userSubject(),
            first.sandboxId(),
            first.orderId(),
            "payment-full-intent",
            new MockPaymentRequest(1800L, "CNY", null));
    MockPaymentCallbackRequest callback =
        evaluationCallback(attempt, first.sandboxId(), "full-intent");
    MockPaymentCallbackRequest crossCorrelation =
        evaluationCallbackWith(
            callback,
            callback.callbackEventId(),
            callback.callbackCorrelationId(),
            callback.orderId(),
            callback.amountMinor(),
            callback.currency(),
            callback.outcome(),
            second.sandboxId(),
            callback.supportSessionId(),
            callback.traceId(),
            callback.operationId());
    MockPaymentCallbackRequest unknownCorrelation =
        evaluationCallbackWith(
            crossCorrelation,
            crossCorrelation.callbackEventId(),
            UUID.randomUUID().toString(),
            crossCorrelation.orderId(),
            crossCorrelation.amountMinor(),
            crossCorrelation.currency(),
            crossCorrelation.outcome(),
            crossCorrelation.sandboxId(),
            crossCorrelation.supportSessionId(),
            crossCorrelation.traceId(),
            crossCorrelation.operationId());
    assertThat(paymentFailure(() -> evaluation.callback("callback-cross", crossCorrelation)))
        .isEqualTo(
            paymentFailure(() -> evaluation.callback("callback-unknown", unknownCorrelation)));

    String callbackKey = "callback-full-intent";
    assertThat(evaluation.callback(callbackKey, callback).replayed()).isFalse();
    assertThat(evaluation.callback(callbackKey, callback).replayed()).isTrue();
    List<Runnable> conflicts =
        List.of(
            () -> evaluation.callback("callback-full-intent-new-key", callback),
            () ->
                evaluation.callback(
                    callbackKey,
                    evaluationCallbackWith(
                        callback,
                        UUID.randomUUID().toString(),
                        callback.callbackCorrelationId(),
                        callback.orderId(),
                        callback.amountMinor(),
                        callback.currency(),
                        callback.outcome(),
                        callback.sandboxId(),
                        callback.supportSessionId(),
                        callback.traceId(),
                        callback.operationId())),
            () ->
                evaluation.callback(
                    callbackKey,
                    evaluationCallbackWith(
                        callback,
                        callback.callbackEventId(),
                        callback.callbackCorrelationId(),
                        callback.orderId(),
                        callback.amountMinor(),
                        callback.currency(),
                        callback.outcome(),
                        callback.sandboxId(),
                        "changed-session",
                        callback.traceId(),
                        callback.operationId())),
            () ->
                evaluation.callback(
                    callbackKey,
                    evaluationCallbackWith(
                        callback,
                        callback.callbackEventId(),
                        callback.callbackCorrelationId(),
                        callback.orderId(),
                        callback.amountMinor(),
                        callback.currency(),
                        callback.outcome(),
                        callback.sandboxId(),
                        callback.supportSessionId(),
                        "changed-trace",
                        callback.operationId())),
            () ->
                evaluation.callback(
                    callbackKey,
                    evaluationCallbackWith(
                        callback,
                        callback.callbackEventId(),
                        callback.callbackCorrelationId(),
                        callback.orderId(),
                        callback.amountMinor(),
                        callback.currency(),
                        callback.outcome(),
                        callback.sandboxId(),
                        callback.supportSessionId(),
                        callback.traceId(),
                        "b".repeat(64))));
    for (Runnable conflict : conflicts) {
      assertThat(paymentFailure(conflict)).startsWith("409:CONFLICT:");
    }
    assertPaidTruth(first.orderId(), attempt.attemptId(), "STANDARD_PAYMENT", 1800, "CNY");
    assertThat(callbackCount(attempt.attemptId())).isOne();
  }

  @Test
  void evaluationPaymentOrderingUsesItsDocumentedTotalOrderAndExercisesTheTieKey() {
    EvaluationPaymentFixture fixture = seedEvaluationPayment("payment-ordering");
    MockPaymentService evaluation = evaluationPayments(new MockPaymentRepository(jdbc));
    MockPaymentResult first =
        evaluation.start(
            fixture.userSubject(),
            fixture.sandboxId(),
            fixture.orderId(),
            "payment-ordering-first",
            new MockPaymentRequest(1800L, "CNY", null));
    String secondOrder = seedAdditionalEvaluationOrder(fixture);
    MockPaymentResult second =
        evaluation.start(
            fixture.userSubject(),
            fixture.sandboxId(),
            secondOrder,
            "payment-ordering-second",
            new MockPaymentRequest(1800L, "CNY", null));
    jdbc.update(
        "UPDATE mock_payment_attempt SET created_at = '2035-01-02 03:04:05.123456' "
            + "WHERE attempt_id IN (?, ?)",
        first.attemptId(),
        second.attemptId());

    EvaluationViewRepository views = new EvaluationViewRepository(jdbc);
    List<String> expected =
        List.of(first.attemptId(), second.attemptId()).stream().sorted().toList();
    List<EvaluationViewRepository.PaymentView> firstRead = views.payments(fixture.sandboxId());
    List<EvaluationViewRepository.PaymentView> secondRead = views.payments(fixture.sandboxId());
    assertThat(firstRead.stream().map(EvaluationViewRepository.PaymentView::attemptId).toList())
        .containsExactlyElementsOf(expected);
    assertThat(secondRead).isEqualTo(firstRead);
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
          public void insertCallback(CallbackRecord record, Instant createdAt) {
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
  void callbackRejectsUnknownMismatchedIneligibleAndConflictingTruthWithoutWrites() {
    long callbacksBefore = count("mock_payment_callback");
    long movementsBefore = count("inventory_ledger");
    MockPaymentCallbackRequest unknown =
        new MockPaymentCallbackRequest(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            1L,
            "AUD",
            "SUCCEEDED");
    assertCallbackRejected("callback-unknown", unknown, 404);
    assertThat(count("mock_payment_callback")).isEqualTo(callbacksBefore);
    assertThat(count("inventory_ledger")).isEqualTo(movementsBefore);

    String mismatchedOrderId = seedStandardOrder(USER, 2300);
    MockPaymentResult mismatched =
        payments.start(
            USER,
            mismatchedOrderId,
            "payment-mismatched-callback",
            new MockPaymentRequest(2300L, "AUD", null));
    MockPaymentCallbackRequest wrongAmount =
        new MockPaymentCallbackRequest(
            UUID.randomUUID().toString(),
            mismatched.callbackCorrelationId(),
            mismatched.orderId(),
            2301L,
            "AUD",
            "SUCCEEDED");
    assertCallbackRejected("callback-wrong-amount", wrongAmount, 409);
    MockPaymentCallbackRequest wrongOrder =
        new MockPaymentCallbackRequest(
            UUID.randomUUID().toString(),
            mismatched.callbackCorrelationId(),
            UUID.randomUUID().toString(),
            2300L,
            "AUD",
            "SUCCEEDED");
    assertCallbackRejected("callback-wrong-order", wrongOrder, 409);
    assertThat(attemptState(mismatched.attemptId())).containsExactly("PENDING", "1");
    assertThat(paymentMovementCount(mismatched.attemptId())).isZero();
    assertThat(count("mock_payment_callback")).isEqualTo(callbacksBefore);
    assertThat(count("inventory_ledger")).isEqualTo(movementsBefore);

    assertThat(
            jdbc.update(
                "UPDATE mock_payment_attempt SET state = 'FAILED', state_version = 2 "
                    + "WHERE attempt_id = ? AND state = 'PENDING' AND state_version = 1",
                mismatched.attemptId()))
        .isOne();
    assertCallbackRejected(
        "callback-failed-attempt", callback(mismatched, UUID.randomUUID().toString()), 409);
    assertThat(attemptState(mismatched.attemptId())).containsExactly("FAILED", "2");
    assertThat(paymentMovementCount(mismatched.attemptId())).isZero();
    assertThat(count("mock_payment_callback")).isEqualTo(callbacksBefore);
    assertThat(count("inventory_ledger")).isEqualTo(movementsBefore);

    String appliedOrderId = seedStandardOrder(USER, 2400);
    MockPaymentResult appliedAttempt =
        payments.start(
            USER,
            appliedOrderId,
            "payment-conflict-applied",
            new MockPaymentRequest(2400L, "AUD", null));
    String existingEventId = UUID.randomUUID().toString();
    payments.callback("callback-conflict-shared", callback(appliedAttempt, existingEventId));

    String pendingOrderId = seedStandardOrder(USER, 2500);
    MockPaymentResult pendingAttempt =
        payments.start(
            USER,
            pendingOrderId,
            "payment-conflict-pending",
            new MockPaymentRequest(2500L, "AUD", null));
    long callbacksAfterApplied = count("mock_payment_callback");
    long movementsAfterApplied = count("inventory_ledger");
    MockPaymentCallbackRequest pendingCallback =
        callback(pendingAttempt, UUID.randomUUID().toString());
    assertCallbackRejected("callback-conflict-shared", pendingCallback, 409);
    MockPaymentCallbackRequest reusedEvent = callback(pendingAttempt, existingEventId);
    assertCallbackRejected("callback-conflict-new-key", reusedEvent, 409);
    MockPaymentCallbackRequest reusedCorrelation =
        new MockPaymentCallbackRequest(
            UUID.randomUUID().toString(),
            appliedAttempt.callbackCorrelationId(),
            pendingAttempt.orderId(),
            pendingAttempt.amountMinor(),
            pendingAttempt.currency(),
            "SUCCEEDED");
    assertCallbackRejected("callback-conflict-correlation", reusedCorrelation, 409);
    assertThat(attemptState(pendingAttempt.attemptId())).containsExactly("PENDING", "1");
    assertThat(paymentMovementCount(pendingAttempt.attemptId())).isZero();
    assertThat(count("mock_payment_callback")).isEqualTo(callbacksAfterApplied);
    assertThat(count("inventory_ledger")).isEqualTo(movementsAfterApplied);
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
  void evaluationConcurrentDuplicateCallbackCommitsOneAuditAndMovement() throws Exception {
    for (int iteration = 0; iteration < 10; iteration++) {
      EvaluationPaymentFixture fixture = seedEvaluationPayment("duplicate-" + iteration);
      MockPaymentService evaluationPayments = evaluationPayments(new MockPaymentRepository(jdbc));
      MockPaymentResult attempt =
          evaluationPayments.start(
              fixture.userSubject(),
              fixture.sandboxId(),
              fixture.orderId(),
              "payment-eval-duplicate-" + iteration,
              new MockPaymentRequest(1800L, "CNY", null));
      MockPaymentCallbackRequest callback =
          evaluationCallback(attempt, fixture.sandboxId(), "duplicate-" + iteration);
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch release = new CountDownLatch(1);
      String callbackKey = "callback-eval-duplicate-" + iteration;

      CompletableFuture<MockPaymentCallbackResult> first =
          CompletableFuture.supplyAsync(
              () -> concurrentCallback(evaluationPayments, ready, release, callbackKey, callback));
      CompletableFuture<MockPaymentCallbackResult> second =
          CompletableFuture.supplyAsync(
              () -> concurrentCallback(evaluationPayments, ready, release, callbackKey, callback));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      release.countDown();

      assertThat(first.get(10, TimeUnit.SECONDS).replayed())
          .isNotEqualTo(second.get(10, TimeUnit.SECONDS).replayed());
      assertPaidTruth(fixture.orderId(), attempt.attemptId(), "STANDARD_PAYMENT", 1800, "CNY");
      assertThat(paymentAuditCount(fixture.sandboxId())).isOne();
    }
  }

  @Test
  void committedEvaluationCallbackReplayUsesDurableTruthAfterSandboxDeath() {
    EvaluationPaymentFixture fixture = seedEvaluationPayment("committed-replay-dead");
    MockPaymentService evaluation = evaluationPayments(new MockPaymentRepository(jdbc));
    MockPaymentResult attempt =
        evaluation.start(
            fixture.userSubject(),
            fixture.sandboxId(),
            fixture.orderId(),
            "payment-committed-replay-dead",
            new MockPaymentRequest(1800L, "CNY", null));
    MockPaymentCallbackRequest callback =
        evaluationCallback(attempt, fixture.sandboxId(), "committed-replay-dead");
    String callbackKey = "callback-committed-replay-dead";

    assertThat(evaluation.callback(callbackKey, callback).replayed()).isFalse();
    completeEvaluationSandbox(fixture, "complete-committed-replay-dead");

    assertThat(evaluation.callback(callbackKey, callback).replayed()).isTrue();
    assertPaidTruth(fixture.orderId(), attempt.attemptId(), "STANDARD_PAYMENT", 1800, "CNY");
    assertThat(paymentAuditCount(fixture.sandboxId())).isOne();
  }

  @Test
  void committedEvaluationCallbackIntegrityFailurePrecedesInactiveClassification() {
    EvaluationPaymentFixture fixture = seedEvaluationPayment("committed-replay-corrupt");
    MockPaymentService evaluation = evaluationPayments(new MockPaymentRepository(jdbc));
    MockPaymentResult attempt =
        evaluation.start(
            fixture.userSubject(),
            fixture.sandboxId(),
            fixture.orderId(),
            "payment-committed-replay-corrupt",
            new MockPaymentRequest(1800L, "CNY", null));
    MockPaymentCallbackRequest callback =
        evaluationCallback(attempt, fixture.sandboxId(), "committed-replay-corrupt");
    String callbackKey = "callback-committed-replay-corrupt";
    evaluation.callback(callbackKey, callback);
    jdbc.update(
        "UPDATE mock_payment_attempt SET callback_correlation_id = ? WHERE attempt_id = ?",
        UUID.randomUUID().toString(),
        attempt.attemptId());
    completeEvaluationSandbox(fixture, "complete-committed-replay-corrupt");

    assertThatThrownBy(() -> evaluation.callback(callbackKey, callback))
        .isInstanceOfSatisfying(
            MockPaymentException.class, exception -> assertThat(exception.status()).isEqualTo(409));
  }

  @Test
  void committedEvaluationCallbackDuplicateCorrelationIsAnIntegrityConflict() {
    EvaluationPaymentFixture fixture = seedEvaluationPayment("committed-replay-duplicate");
    MockPaymentService evaluation = evaluationPayments(new MockPaymentRepository(jdbc));
    MockPaymentResult attempt =
        evaluation.start(
            fixture.userSubject(),
            fixture.sandboxId(),
            fixture.orderId(),
            "payment-committed-replay-duplicate",
            new MockPaymentRequest(1800L, "CNY", null));
    MockPaymentCallbackRequest callback =
        evaluationCallback(attempt, fixture.sandboxId(), "committed-replay-duplicate");
    String callbackKey = "callback-committed-replay-duplicate";
    evaluation.callback(callbackKey, callback);
    assertThat(
            jdbc.update(
                """
                INSERT INTO mock_payment_callback
                  (callback_event_id, callback_idempotency_key, attempt_id,
                   callback_correlation_id, sandbox_id, support_session_id, trace_id,
                   operation_id, intent_hash, requested_outcome, result_state, created_at)
                SELECT ?, ?, ?, callback_correlation_id, sandbox_id, support_session_id, trace_id,
                       operation_id, intent_hash, requested_outcome, result_state, created_at
                FROM mock_payment_callback WHERE attempt_id = ?
                """,
                UUID.randomUUID().toString(),
                "duplicate-correlation-key",
                UUID.randomUUID().toString(),
                attempt.attemptId()))
        .isOne();

    assertThatThrownBy(() -> evaluation.callback(callbackKey, callback))
        .isInstanceOfSatisfying(
            MockPaymentException.class, exception -> assertThat(exception.status()).isEqualTo(409));
  }

  @Test
  void committedEvaluationCallbackReplayDoesNotDependOnLivenessRead() {
    EvaluationPaymentFixture fixture = seedEvaluationPayment("committed-replay-unavailable");
    MockPaymentService healthy = evaluationPayments(new MockPaymentRepository(jdbc));
    MockPaymentResult attempt =
        healthy.start(
            fixture.userSubject(),
            fixture.sandboxId(),
            fixture.orderId(),
            "payment-committed-replay-unavailable",
            new MockPaymentRequest(1800L, "CNY", null));
    MockPaymentCallbackRequest callback =
        evaluationCallback(attempt, fixture.sandboxId(), "committed-replay-unavailable");
    String callbackKey = "callback-committed-replay-unavailable";
    healthy.callback(callbackKey, callback);
    AtomicInteger livenessReads = new AtomicInteger();
    EvaluationSandboxRepository unavailable =
        new EvaluationSandboxRepository(jdbc) {
          @Override
          public Sandbox lockForPayment(String sandboxId) {
            livenessReads.incrementAndGet();
            throw new QueryTimeoutException("controlled liveness timeout");
          }
        };
    MockPaymentService replay = evaluationPayments(new MockPaymentRepository(jdbc), unavailable);

    assertThat(replay.callback(callbackKey, callback).replayed()).isTrue();
    assertThat(livenessReads).hasValue(0);
  }

  @Test
  void committedEvaluationCallbackReplayRechecksTruthAfterWaitingForConcurrentCommit()
      throws Exception {
    EvaluationPaymentFixture fixture = seedEvaluationPayment("committed-toctou");
    MockPaymentResult attempt =
        evaluationPayments(new MockPaymentRepository(jdbc))
            .start(
                fixture.userSubject(),
                fixture.sandboxId(),
                fixture.orderId(),
                "payment-committed-toctou",
                new MockPaymentRequest(1800L, "CNY", null));
    MockPaymentCallbackRequest callback =
        evaluationCallback(attempt, fixture.sandboxId(), "committed-toctou");
    String callbackKey = "callback-committed-toctou";
    CountDownLatch writerHasPersistedTruth = new CountDownLatch(1);
    CountDownLatch releaseWriterCommit = new CountDownLatch(1);
    CountDownLatch replayHasLockedCommittedAttempt = new CountDownLatch(1);
    CountDownLatch releaseReplay = new CountDownLatch(1);
    MockPaymentRepository pausedWriter =
        new MockPaymentRepository(jdbc) {
          @Override
          public void insertPaymentAuditReference(
              String auditReferenceId,
              CallbackRecord callback,
              long entityVersion,
              Instant createdAt) {
            super.insertPaymentAuditReference(auditReferenceId, callback, entityVersion, createdAt);
            writerHasPersistedTruth.countDown();
            awaitSignal(releaseWriterCommit, "writer commit release");
          }
        };
    MockPaymentRepository waitingReplay =
        new MockPaymentRepository(jdbc) {
          @Override
          public java.util.Optional<AttemptRecord> findEvaluationAttemptByCorrelationForUpdate(
              String correlationId, String sandboxId) {
            java.util.Optional<AttemptRecord> committed =
                super.findEvaluationAttemptByCorrelationForUpdate(correlationId, sandboxId);
            replayHasLockedCommittedAttempt.countDown();
            awaitSignal(releaseReplay, "replay release");
            return committed;
          }
        };
    MockPaymentService writer = evaluationPayments(pausedWriter);
    MockPaymentService replay = evaluationPayments(waitingReplay);

    CompletableFuture<MockPaymentCallbackResult> writerFuture =
        CompletableFuture.supplyAsync(() -> writer.callback(callbackKey, callback));
    assertThat(writerHasPersistedTruth.await(10, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<MockPaymentCallbackResult> replayFuture =
        CompletableFuture.supplyAsync(() -> replay.callback(callbackKey, callback));
    assertThat(replayFuture).isNotDone();

    releaseWriterCommit.countDown();
    assertThat(writerFuture.get(10, TimeUnit.SECONDS).replayed()).isFalse();
    assertThat(replayHasLockedCommittedAttempt.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(completeEvaluationSandbox(fixture, "complete-committed-toctou").lifecycleState())
        .isEqualTo("DEAD");
    releaseReplay.countDown();

    assertThat(replayFuture.get(10, TimeUnit.SECONDS).replayed()).isTrue();
    assertPaidTruth(fixture.orderId(), attempt.attemptId(), "STANDARD_PAYMENT", 1800, "CNY");
    assertThat(paymentAuditCount(fixture.sandboxId())).isOne();
  }

  @Test
  void evaluationCallbackWinsAControlledCompletionRace() throws Exception {
    EvaluationPaymentFixture fixture = seedEvaluationPayment("callback-wins");
    MockPaymentService healthy = evaluationPayments(new MockPaymentRepository(jdbc));
    MockPaymentResult attempt =
        healthy.start(
            fixture.userSubject(),
            fixture.sandboxId(),
            fixture.orderId(),
            "payment-eval-callback-wins",
            new MockPaymentRequest(1800L, "CNY", null));
    MockPaymentCallbackRequest callback =
        evaluationCallback(attempt, fixture.sandboxId(), "callback-wins");
    CountDownLatch callbackHasSandboxLock = new CountDownLatch(1);
    CountDownLatch releaseCallback = new CountDownLatch(1);
    CountDownLatch completionEntered = new CountDownLatch(1);
    EvaluationSandboxRepository pausedSandbox =
        new EvaluationSandboxRepository(jdbc) {
          @Override
          public Sandbox lockForPayment(String sandboxId) {
            Sandbox locked = super.lockForPayment(sandboxId);
            callbackHasSandboxLock.countDown();
            awaitSignal(releaseCallback, "callback release");
            return locked;
          }
        };
    MockPaymentService callbackService =
        evaluationPayments(new MockPaymentRepository(jdbc), pausedSandbox);
    EvaluationSandboxRepository completionRepository =
        new EvaluationSandboxRepository(jdbc) {
          @Override
          public Sandbox beginCompletion(
              String sandboxId, String caseCorrelation, String idempotencyKey, Instant now) {
            completionEntered.countDown();
            return super.beginCompletion(sandboxId, caseCorrelation, idempotencyKey, now);
          }
        };

    CompletableFuture<MockPaymentCallbackResult> callbackFuture =
        CompletableFuture.supplyAsync(
            () -> callbackService.callback("callback-eval-callback-wins", callback));
    assertThat(callbackHasSandboxLock.await(10, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<EvaluationSandboxRepository.Sandbox> completionFuture =
        CompletableFuture.supplyAsync(
            () ->
                transactionTemplate()
                    .execute(
                        status ->
                            completionRepository.beginCompletion(
                                fixture.sandboxId(),
                                fixture.caseCorrelation(),
                                "complete-eval-callback-wins",
                                Instant.now())));
    assertThat(completionEntered.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(completionFuture).isNotDone();
    releaseCallback.countDown();

    assertThat(callbackFuture.get(10, TimeUnit.SECONDS).replayed()).isFalse();
    assertThat(completionFuture.get(10, TimeUnit.SECONDS).lifecycleState()).isEqualTo("DEAD");
    assertPaidTruth(fixture.orderId(), attempt.attemptId(), "STANDARD_PAYMENT", 1800, "CNY");
    assertThat(paymentAuditCount(fixture.sandboxId())).isOne();
  }

  @Test
  void evaluationCompletionWinsAControlledCallbackRace() throws Exception {
    EvaluationPaymentFixture fixture = seedEvaluationPayment("completion-wins");
    MockPaymentService healthy = evaluationPayments(new MockPaymentRepository(jdbc));
    MockPaymentResult attempt =
        healthy.start(
            fixture.userSubject(),
            fixture.sandboxId(),
            fixture.orderId(),
            "payment-eval-completion-wins",
            new MockPaymentRequest(1800L, "CNY", null));
    MockPaymentCallbackRequest callback =
        evaluationCallback(attempt, fixture.sandboxId(), "completion-wins");
    CountDownLatch completionHasSandboxLock = new CountDownLatch(1);
    CountDownLatch releaseCompletion = new CountDownLatch(1);
    CountDownLatch callbackEntered = new CountDownLatch(1);
    EvaluationSandboxRepository completionRepository =
        new EvaluationSandboxRepository(jdbc) {
          @Override
          public Sandbox beginCompletion(
              String sandboxId, String caseCorrelation, String idempotencyKey, Instant now) {
            Sandbox completed =
                super.beginCompletion(sandboxId, caseCorrelation, idempotencyKey, now);
            completionHasSandboxLock.countDown();
            awaitSignal(releaseCompletion, "completion release");
            return completed;
          }
        };
    EvaluationSandboxRepository observingSandboxRepository =
        new EvaluationSandboxRepository(jdbc) {
          @Override
          public Sandbox lockForPayment(String sandboxId) {
            callbackEntered.countDown();
            return super.lockForPayment(sandboxId);
          }
        };
    MockPaymentService callbackService =
        evaluationPayments(new MockPaymentRepository(jdbc), observingSandboxRepository);

    CompletableFuture<EvaluationSandboxRepository.Sandbox> completionFuture =
        CompletableFuture.supplyAsync(
            () ->
                transactionTemplate()
                    .execute(
                        status ->
                            completionRepository.beginCompletion(
                                fixture.sandboxId(),
                                fixture.caseCorrelation(),
                                "complete-eval-completion-wins",
                                Instant.now())));
    assertThat(completionHasSandboxLock.await(10, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Object> callbackFuture =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return callbackService.callback("callback-eval-completion-wins", callback);
              } catch (EvaluationSandboxException exception) {
                return exception;
              }
            });
    assertThat(callbackEntered.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(callbackFuture).isNotDone();
    releaseCompletion.countDown();

    assertThat(completionFuture.get(10, TimeUnit.SECONDS).lifecycleState()).isEqualTo("DEAD");
    assertThat(callbackFuture.get(10, TimeUnit.SECONDS))
        .isInstanceOfSatisfying(
            EvaluationSandboxException.class,
            exception -> assertThat(exception.status()).isEqualTo(403));
    assertThat(orderState(fixture.orderId())).containsExactly("UNPAID", "1");
    assertThat(attemptState(attempt.attemptId())).containsExactly("PENDING", "1");
    assertThat(paymentMovementCount(attempt.attemptId())).isZero();
    assertThat(callbackCount(attempt.attemptId())).isZero();
    assertThat(paymentAuditCount(fixture.sandboxId())).isZero();
  }

  @Test
  void evaluationCallbackRechecksExpiryAfterWaitingForSandboxLock() throws Exception {
    EvaluationPaymentFixture fixture = seedEvaluationPayment("expiry-wait");
    MockPaymentService healthy = evaluationPayments(new MockPaymentRepository(jdbc));
    MockPaymentResult attempt =
        healthy.start(
            fixture.userSubject(),
            fixture.sandboxId(),
            fixture.orderId(),
            "payment-eval-expiry-wait",
            new MockPaymentRequest(1800L, "CNY", null));
    MockPaymentCallbackRequest callback =
        evaluationCallback(attempt, fixture.sandboxId(), "expiry-wait");
    MutableClock callbackClock = new MutableClock(Instant.now(), ZoneId.of("UTC"));
    Instant expiry = callbackClock.instant().plusSeconds(10);
    jdbc.update(
        "UPDATE eval_sandbox SET expires_at = ? WHERE sandbox_id = ?",
        java.sql.Timestamp.from(expiry),
        fixture.sandboxId());
    CountDownLatch holderHasLock = new CountDownLatch(1);
    CountDownLatch releaseHolder = new CountDownLatch(1);
    CountDownLatch callbackEntered = new CountDownLatch(1);
    EvaluationSandboxRepository observingSandboxRepository =
        new EvaluationSandboxRepository(jdbc) {
          @Override
          public Sandbox lockForPayment(String sandboxId) {
            callbackEntered.countDown();
            return super.lockForPayment(sandboxId);
          }
        };
    MockPaymentService callbackService =
        evaluationPayments(
            new MockPaymentRepository(jdbc), observingSandboxRepository, callbackClock);

    CompletableFuture<Boolean> lockHolder =
        CompletableFuture.supplyAsync(
            () ->
                transactionTemplate()
                    .execute(
                        status -> {
                          new EvaluationSandboxRepository(jdbc).lockForPayment(fixture.sandboxId());
                          holderHasLock.countDown();
                          awaitSignal(releaseHolder, "sandbox lock holder release");
                          return true;
                        }));
    assertThat(holderHasLock.await(10, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Object> callbackFuture =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return callbackService.callback("callback-eval-expiry-wait", callback);
              } catch (EvaluationSandboxException exception) {
                return exception;
              }
            });
    assertThat(callbackEntered.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(callbackClock.instant()).isBefore(expiry);
    assertThat(callbackFuture).isNotDone();
    callbackClock.set(expiry.plusSeconds(1));
    assertThat(callbackClock.instant()).isAfter(expiry);
    releaseHolder.countDown();

    assertThat(lockHolder.get(10, TimeUnit.SECONDS)).isTrue();
    assertThat(callbackFuture.get(10, TimeUnit.SECONDS))
        .isInstanceOfSatisfying(
            EvaluationSandboxException.class,
            exception -> assertThat(exception.status()).isEqualTo(403));
    assertThat(orderState(fixture.orderId())).containsExactly("UNPAID", "1");
    assertThat(attemptState(attempt.attemptId())).containsExactly("PENDING", "1");
    assertThat(paymentMovementCount(attempt.attemptId())).isZero();
    assertThat(callbackCount(attempt.attemptId())).isZero();
    assertThat(paymentAuditCount(fixture.sandboxId())).isZero();
  }

  @Test
  void evaluationCallbackRollsBackAfterEveryIntermediateWriteAndRetryConverges() {
    for (FailurePoint failurePoint : FailurePoint.values()) {
      EvaluationPaymentFixture fixture = seedEvaluationPayment("rollback-" + failurePoint.name());
      MockPaymentService healthy = evaluationPayments(new MockPaymentRepository(jdbc));
      MockPaymentResult attempt =
          healthy.start(
              fixture.userSubject(),
              fixture.sandboxId(),
              fixture.orderId(),
              "payment-eval-rollback-" + failurePoint.name(),
              new MockPaymentRequest(1800L, "CNY", null));
      MockPaymentCallbackRequest callback =
          evaluationCallback(attempt, fixture.sandboxId(), failurePoint.name());
      MockPaymentService failing = evaluationPayments(failingRepository(failurePoint));
      String callbackKey = "callback-eval-rollback-" + failurePoint.name();

      assertThatThrownBy(() -> failing.callback(callbackKey, callback))
          .isInstanceOf(DataAccessResourceFailureException.class);
      assertThat(orderState(fixture.orderId())).containsExactly("UNPAID", "1");
      assertThat(attemptState(attempt.attemptId())).containsExactly("PENDING", "1");
      assertThat(paymentMovementCount(attempt.attemptId())).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM mock_payment_callback WHERE attempt_id = ?",
                  Long.class,
                  attempt.attemptId()))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM eval_commerce_audit_reference WHERE sandbox_id = ?",
                  Long.class,
                  fixture.sandboxId()))
          .isZero();

      assertThat(healthy.callback(callbackKey, callback).state()).isEqualTo("SUCCEEDED");
      assertPaidTruth(fixture.orderId(), attempt.attemptId(), "STANDARD_PAYMENT", 1800, "CNY");
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM eval_commerce_audit_reference WHERE sandbox_id = ?",
                  Long.class,
                  fixture.sandboxId()))
          .isOne();
    }
  }

  @Test
  void callbackLockAndConstraintCompetitionResolveFromCommittedTruth() {
    String orderId = seedStandardOrder(USER, 3500);
    MockPaymentResult attempt =
        payments.start(
            USER, orderId, "payment-deadlock", new MockPaymentRequest(3500L, "AUD", null));
    MockPaymentCallbackRequest callback = callback(attempt, UUID.randomUUID().toString());
    payments.callback("callback-deadlock", callback);

    AtomicInteger deadlockCalls = new AtomicInteger();
    AtomicInteger deadlockObservations = new AtomicInteger();
    MockPaymentRepository oneDeadlock =
        new MockPaymentRepository(jdbc) {
          @Override
          public java.util.Optional<CallbackRecord> findCallbackByKey(String idempotencyKey) {
            if (deadlockObservations.incrementAndGet() <= 2) {
              return java.util.Optional.empty();
            }
            return super.findCallbackByKey(idempotencyKey);
          }

          @Override
          public java.util.Optional<CallbackRecord> findCallbackByEvent(String eventId) {
            if (deadlockObservations.incrementAndGet() <= 2) {
              return java.util.Optional.empty();
            }
            return super.findCallbackByEvent(eventId);
          }

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
    AtomicInteger timeoutObservations = new AtomicInteger();
    MockPaymentRepository lockTimeout =
        new MockPaymentRepository(jdbc) {
          @Override
          public java.util.Optional<CallbackRecord> findCallbackByKey(String idempotencyKey) {
            if (timeoutObservations.incrementAndGet() <= 2) {
              return java.util.Optional.empty();
            }
            return super.findCallbackByKey(idempotencyKey);
          }

          @Override
          public java.util.Optional<CallbackRecord> findCallbackByEvent(String eventId) {
            if (timeoutObservations.incrementAndGet() <= 2) {
              return java.util.Optional.empty();
            }
            return super.findCallbackByEvent(eventId);
          }

          @Override
          public java.util.Optional<AttemptRecord> findAttemptByCorrelationForUpdate(
              String correlationId) {
            timeoutCalls.incrementAndGet();
            throw lockFailure(1205);
          }
        };
    MockPaymentService nonRetrying =
        new MockPaymentService(lockTimeout, transactionTemplate(), Clock.systemUTC());

    MockPaymentCallbackResult timeoutConverged =
        nonRetrying.callback("callback-deadlock", callback);
    assertThat(timeoutConverged.replayed()).isTrue();
    assertThat(timeoutCalls).hasValue(2);
    assertThat(paymentMovementCount(attempt.attemptId())).isOne();

    AtomicInteger duplicateCalls = new AtomicInteger();
    AtomicInteger duplicateObservations = new AtomicInteger();
    MockPaymentRepository duplicateConflict =
        new MockPaymentRepository(jdbc) {
          @Override
          public java.util.Optional<CallbackRecord> findCallbackByKey(String idempotencyKey) {
            if (duplicateObservations.incrementAndGet() <= 2) {
              return java.util.Optional.empty();
            }
            return super.findCallbackByKey(idempotencyKey);
          }

          @Override
          public java.util.Optional<CallbackRecord> findCallbackByEvent(String eventId) {
            if (duplicateObservations.incrementAndGet() <= 2) {
              return java.util.Optional.empty();
            }
            return super.findCallbackByEvent(eventId);
          }

          @Override
          public java.util.Optional<AttemptRecord> findAttemptByCorrelationForUpdate(
              String correlationId) {
            duplicateCalls.incrementAndGet();
            throw new DuplicateKeyException("controlled callback uniqueness conflict");
          }
        };
    MockPaymentService duplicateRejecting =
        new MockPaymentService(duplicateConflict, transactionTemplate(), Clock.systemUTC());

    MockPaymentCallbackResult duplicateConverged =
        duplicateRejecting.callback("callback-deadlock", callback);
    assertThat(duplicateConverged.replayed()).isTrue();
    assertThat(duplicateCalls).hasValue(1);
    assertThat(paymentMovementCount(attempt.attemptId())).isOne();

    String uncommittedOrderId = seedStandardOrder(USER, 3600);
    MockPaymentResult uncommittedAttempt =
        payments.start(
            USER,
            uncommittedOrderId,
            "payment-lock-timeout-uncommitted",
            new MockPaymentRequest(3600L, "AUD", null));
    MockPaymentCallbackRequest uncommittedCallback =
        callback(uncommittedAttempt, UUID.randomUUID().toString());
    AtomicInteger uncommittedTimeoutCalls = new AtomicInteger();
    MockPaymentRepository uncommittedTimeout =
        new MockPaymentRepository(jdbc) {
          @Override
          public java.util.Optional<AttemptRecord> findAttemptByCorrelationForUpdate(
              String correlationId) {
            uncommittedTimeoutCalls.incrementAndGet();
            throw lockFailure(1205);
          }
        };
    MockPaymentService uncommittedRejecting =
        new MockPaymentService(uncommittedTimeout, transactionTemplate(), Clock.systemUTC());

    assertThatThrownBy(
            () ->
                uncommittedRejecting.callback(
                    "callback-lock-timeout-uncommitted", uncommittedCallback))
        .isInstanceOfSatisfying(
            MockPaymentException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.category()).isEqualTo("CONFLICT");
            });
    assertThat(uncommittedTimeoutCalls).hasValue(2);
    assertThat(paymentMovementCount(uncommittedAttempt.attemptId())).isZero();
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
    return start(token, null, orderId, idempotencyKey, request);
  }

  private ResponseEntity<String> startRaw(
      String token, String orderId, String idempotencyKey, Map<String, Object> request) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.set("Idempotency-Key", idempotencyKey);
    return http.exchange(
        "/api/orders/" + orderId + "/mock-payment",
        HttpMethod.POST,
        new HttpEntity<>(request, headers),
        String.class);
  }

  private ResponseEntity<MockPaymentResult> start(
      String token,
      String sandboxId,
      String orderId,
      String idempotencyKey,
      Map<String, Object> request) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    if (sandboxId != null) {
      headers.set("X-Eval-Sandbox-Id", sandboxId);
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

  private ResponseEntity<String> callbackRaw(
      MockPaymentCallbackRequest signedRequest,
      String idempotencyKey,
      Map<String, Object> requestBody) {
    HttpHeaders headers = new HttpHeaders();
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    headers.set("Idempotency-Key", idempotencyKey);
    headers.set("X-Mock-Payment-Key-Id", CALLBACK_KEY);
    headers.set("X-Mock-Payment-Timestamp", timestamp);
    String canonical =
        MockPaymentCallbackAuthenticator.canonical(
            CALLBACK_KEY, timestamp, idempotencyKey, signedRequest);
    headers.set(
        "X-Mock-Payment-Signature",
        HexFormat.of()
            .formatHex(MockPaymentCallbackAuthenticator.hmac(CALLBACK_SECRET, canonical)));
    return http.exchange(
        "/internal/mock-payments/callback",
        HttpMethod.POST,
        new HttpEntity<>(requestBody, headers),
        String.class);
  }

  private ResponseEntity<String> callbackRawPayload(String idempotencyKey, byte[] requestBody) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", idempotencyKey);
    headers.set("X-Mock-Payment-Key-Id", CALLBACK_KEY);
    headers.set("X-Mock-Payment-Timestamp", Long.toString(Instant.now().getEpochSecond()));
    headers.set("X-Mock-Payment-Signature", "0".repeat(64));
    return http.exchange(
        "/internal/mock-payments/callback",
        HttpMethod.POST,
        new HttpEntity<>(requestBody, headers),
        String.class);
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
    return concurrentCallback(payments, ready, release, idempotencyKey, callback);
  }

  private static MockPaymentCallbackResult concurrentCallback(
      MockPaymentService service,
      CountDownLatch ready,
      CountDownLatch release,
      String idempotencyKey,
      MockPaymentCallbackRequest callback) {
    awaitRace(ready, release);
    return service.callback(idempotencyKey, callback);
  }

  private MockPaymentService evaluationPayments(MockPaymentRepository repository) {
    return evaluationPayments(repository, new EvaluationSandboxRepository(jdbc));
  }

  private MockPaymentService evaluationPayments(
      MockPaymentRepository repository, EvaluationSandboxRepository sandboxRepository) {
    return evaluationPayments(repository, sandboxRepository, Clock.systemUTC());
  }

  private MockPaymentService evaluationPayments(
      MockPaymentRepository repository,
      EvaluationSandboxRepository sandboxRepository,
      Clock paymentClock) {
    return new MockPaymentService(
        repository, transactionTemplate(), paymentClock, sandboxRepository);
  }

  private MockPaymentRepository failingRepository(FailurePoint point) {
    return new MockPaymentRepository(jdbc) {
      private void fail(FailurePoint current) {
        if (point == current) {
          throw new DataAccessResourceFailureException("controlled " + current + " failure");
        }
      }

      @Override
      public void markOrderPaid(OrderTruth order) {
        super.markOrderPaid(order);
        fail(FailurePoint.ORDER);
      }

      @Override
      public void markAttemptSucceeded(AttemptRecord attempt, Instant succeededAt) {
        super.markAttemptSucceeded(attempt, succeededAt);
        fail(FailurePoint.ATTEMPT);
      }

      @Override
      public void insertPaymentMovement(AttemptRecord attempt, OrderTruth order) {
        super.insertPaymentMovement(attempt, order);
        fail(FailurePoint.LEDGER);
      }

      @Override
      public void insertCallback(CallbackRecord callback, Instant createdAt) {
        super.insertCallback(callback, createdAt);
        fail(FailurePoint.CALLBACK);
      }

      @Override
      public void insertPaymentAuditReference(
          String auditReferenceId, CallbackRecord callback, long entityVersion, Instant createdAt) {
        super.insertPaymentAuditReference(auditReferenceId, callback, entityVersion, createdAt);
        fail(FailurePoint.AUDIT);
      }
    };
  }

  private EvaluationPaymentFixture seedEvaluationPayment(String suffix) {
    String compact = suffix.replace('_', '-').toLowerCase(java.util.Locale.ROOT);
    String sandboxId = "payment-sandbox-" + compact;
    String caseCorrelation = "payment-case-" + compact;
    String orderId = UUID.randomUUID().toString();
    String handle = "h" + UUID.randomUUID().toString().replace("-", "") + "x".repeat(10);
    String userSubject = "eval-" + UUID.randomUUID();
    Instant now = Instant.now();
    jdbc.update(
        """
        INSERT INTO eval_sandbox
          (sandbox_id, case_correlation, reset_idempotency_key, fixture_digest, fixture_count,
           test_user_label, requested_ttl_seconds, auth_provision_idempotency_key,
           auth_revoke_idempotency_key, opaque_handle, lifecycle_state,
           auth_invalidation_state, cleanup_due_at, provisioning_due_at,
           auth_expiry_upper_bound, expires_at, activated_at, version)
        VALUES (?, ?, ?, REPEAT('0', 64), 1, ?, 600, ?, ?, ?, 'ACTIVE', 'PROVISIONED',
                ?, ?, ?, ?, ?, 2)
        """,
        sandboxId,
        caseCorrelation,
        "reset-" + compact,
        "user-" + compact,
        "provision-" + compact,
        "revoke-" + compact,
        handle,
        java.sql.Timestamp.from(now.plusSeconds(600)),
        java.sql.Timestamp.from(now.plusSeconds(60)),
        java.sql.Timestamp.from(now.plusSeconds(660)),
        java.sql.Timestamp.from(now.plusSeconds(600)),
        java.sql.Timestamp.from(now));
    jdbc.update(
        """
        INSERT INTO eval_sandbox_product_fixture
          (sandbox_id, product_id, name, description, price_minor, currency,
           stock_quantity, available, publication_version)
        VALUES (?, ?, 'Payment fixture', 'Evaluation payment fixture', 900, 'CNY', 3, TRUE, 1)
        """,
        sandboxId,
        "payment-product-" + compact);
    jdbc.update(
        """
        INSERT INTO standard_order
          (order_id, user_subject, sandbox_id, evaluation_owner_handle, product_id, product_name,
           unit_price_minor, currency, quantity, total_price_minor, product_version)
        VALUES (?, ?, ?, ?, ?, 'Payment fixture', 900, 'CNY', 2, 1800, 1)
        """,
        orderId,
        EvaluationSandboxRepository.fixtureOwner(handle),
        sandboxId,
        handle,
        "payment-product-" + compact);
    return new EvaluationPaymentFixture(sandboxId, caseCorrelation, orderId, userSubject, handle);
  }

  private String seedAdditionalEvaluationOrder(EvaluationPaymentFixture fixture) {
    String orderId = UUID.randomUUID().toString();
    String productId =
        jdbc.queryForObject(
            "SELECT product_id FROM standard_order WHERE order_id = ?",
            String.class,
            fixture.orderId());
    jdbc.update(
        """
        INSERT INTO standard_order
          (order_id, user_subject, sandbox_id, evaluation_owner_handle, product_id, product_name,
           unit_price_minor, currency, quantity, total_price_minor, product_version)
        VALUES (?, ?, ?, ?, ?, 'Payment fixture', 900, 'CNY', 2, 1800, 1)
        """,
        orderId,
        EvaluationSandboxRepository.fixtureOwner(fixture.ownerHandle()),
        fixture.sandboxId(),
        fixture.ownerHandle(),
        productId);
    return orderId;
  }

  private static void awaitRace(CountDownLatch ready, CountDownLatch release) {
    ready.countDown();
    awaitSignal(release, "payment race release");
  }

  private static void awaitSignal(CountDownLatch signal, String description) {
    try {
      if (!signal.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException(description + " timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(description + " was interrupted", exception);
    }
  }

  private static CannotAcquireLockException lockFailure(int errorCode) {
    return new CannotAcquireLockException(
        "controlled MySQL lock failure",
        new SQLException("controlled MySQL lock failure", "40001", errorCode));
  }

  private void assertCallbackRejected(
      String idempotencyKey, MockPaymentCallbackRequest request, int expectedStatus) {
    assertThatThrownBy(() -> payments.callback(idempotencyKey, request))
        .isInstanceOfSatisfying(
            MockPaymentException.class,
            exception -> assertThat(exception.status()).isEqualTo(expectedStatus));
  }

  private void assertPaidTruth(
      String orderId, String attemptId, String movementType, long amountMinor) {
    assertPaidTruth(orderId, attemptId, movementType, amountMinor, "AUD");
  }

  private void assertPaidTruth(
      String orderId, String attemptId, String movementType, long amountMinor, String currency) {
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
                  AND payment_amount_minor = ? AND payment_currency = ?
                  AND inventory_delta = 0 AND activity_quota_delta = 0
                """,
                Long.class,
                "mock-payment:" + attemptId,
                movementType,
                amountMinor,
                currency))
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

  private long callbackCount(String attemptId) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM mock_payment_callback WHERE attempt_id = ?",
            Long.class,
            attemptId);
    return count == null ? 0 : count;
  }

  private long paymentAuditCount(String sandboxId) {
    Long count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM eval_commerce_audit_reference
            WHERE sandbox_id = ? AND entity_type = 'PAYMENT_CALLBACK'
            """,
            Long.class,
            sandboxId);
    return count == null ? 0 : count;
  }

  private EvaluationSandboxRepository.Sandbox completeEvaluationSandbox(
      EvaluationPaymentFixture fixture, String idempotencyKey) {
    return transactionTemplate()
        .execute(
            status ->
                new EvaluationSandboxRepository(jdbc)
                    .beginCompletion(
                        fixture.sandboxId(),
                        fixture.caseCorrelation(),
                        idempotencyKey,
                        Instant.now()));
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

  private static Map<String, Object> mapWith(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2) {
      result.put((String) values[index], values[index + 1]);
    }
    return result;
  }

  private static Map<String, Object> withExtra(
      Map<String, Object> source, String name, Object value) {
    Map<String, Object> result = new LinkedHashMap<>(source);
    result.put(name, value);
    return result;
  }

  private static Map<String, Object> callbackBody(MockPaymentCallbackRequest request) {
    return mapWith(
        "callbackEventId",
        request.callbackEventId(),
        "callbackCorrelationId",
        request.callbackCorrelationId(),
        "orderId",
        request.orderId(),
        "amountMinor",
        request.amountMinor(),
        "currency",
        request.currency(),
        "outcome",
        request.outcome());
  }

  private static String paymentFailure(Runnable work) {
    try {
      work.run();
    } catch (MockPaymentException exception) {
      return exception.status() + ":" + exception.category() + ":" + exception.getMessage();
    }
    throw new AssertionError("Expected payment request to fail");
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

  private static MockPaymentCallbackRequest evaluationCallback(
      MockPaymentResult attempt, String sandboxId, String suffix) {
    return new MockPaymentCallbackRequest(
        UUID.randomUUID().toString(),
        attempt.callbackCorrelationId(),
        attempt.orderId(),
        attempt.amountMinor(),
        attempt.currency(),
        "SUCCEEDED",
        sandboxId,
        "payment-session-" + suffix.toLowerCase(java.util.Locale.ROOT),
        "payment-trace-" + suffix.toLowerCase(java.util.Locale.ROOT),
        "a".repeat(64));
  }

  private static MockPaymentCallbackRequest evaluationCallbackWith(
      MockPaymentCallbackRequest source,
      String callbackEventId,
      String callbackCorrelationId,
      String orderId,
      long amountMinor,
      String currency,
      String outcome,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId) {
    return new MockPaymentCallbackRequest(
        callbackEventId,
        callbackCorrelationId,
        orderId,
        amountMinor,
        currency,
        outcome,
        sandboxId,
        supportSessionId,
        traceId,
        operationId);
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

  private enum FailurePoint {
    ORDER,
    ATTEMPT,
    LEDGER,
    CALLBACK,
    AUDIT
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;
    private final ZoneId zone;

    private MutableClock(Instant initial, ZoneId zone) {
      current = new AtomicReference<>(initial);
      this.zone = zone;
    }

    private void set(Instant instant) {
      current.set(instant);
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
      return new MutableClock(current.get(), requestedZone);
    }

    @Override
    public Instant instant() {
      return current.get();
    }
  }

  private record EvaluationPaymentFixture(
      String sandboxId,
      String caseCorrelation,
      String orderId,
      String userSubject,
      String ownerHandle) {}

  private record SeckillFixture(String orderId, SeckillTimeoutMessage timeout) {}
}
