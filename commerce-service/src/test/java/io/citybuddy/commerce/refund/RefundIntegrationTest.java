package io.citybuddy.commerce.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.citybuddy.commerce.payment.MockPaymentCallbackRequest;
import io.citybuddy.commerce.payment.MockPaymentRepository;
import io.citybuddy.commerce.payment.MockPaymentRequest;
import io.citybuddy.commerce.payment.MockPaymentResult;
import io.citybuddy.commerce.payment.MockPaymentService;
import io.citybuddy.commerce.seckill.SeckillActivityRepository;
import io.citybuddy.commerce.seckill.SeckillCancellationService;
import io.citybuddy.commerce.seckill.SeckillOrderRepository;
import io.citybuddy.commerce.seckill.SeckillReservationRepository;
import io.citybuddy.commerce.seckill.SeckillTimeoutMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
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
class RefundIntegrationTest {
  private static final String USER = "catalog-user";
  private static final String OTHER_USER = "other-user";

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
    registry.add("citybuddy.refund.enabled", () -> "true");
    registry.add("citybuddy.refund.required-permission", () -> "refund:create");
  }

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MockPaymentService payments;
  @Autowired private RefundService refunds;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void directApiEnforcesIdentityIdempotencyEligibilityAndCumulativeAmount() throws Exception {
    PaidFixture paid = seedPaidStandard(1500, "api-main");

    assertThat(request(null, paid.orderId(), "refund-no-token", body(500)).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(request(limitedToken(), paid.orderId(), "refund-limited", body(500)).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(request(otherToken(), paid.orderId(), "refund-other", body(500)).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            request(
                    directToken(),
                    paid.orderId(),
                    "refund-body-owner",
                    Map.of("amountMinor", 500, "currency", "AUD", "userSubject", OTHER_USER))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            request(
                    signedTestToken("https://wrong.example", "citybuddy-web", "direct_user"),
                    paid.orderId(),
                    "refund-wrong-issuer",
                    body(500))
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            request(
                    signedTestToken(
                        "https://identity.citybuddy.test", "wrong-audience", "direct_user"),
                    paid.orderId(),
                    "refund-wrong-audience",
                    body(500))
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            request(
                    signedTestToken("https://identity.citybuddy.test", "citybuddy-web", "service"),
                    paid.orderId(),
                    "refund-wrong-type",
                    body(500))
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            requestWithSandbox(directToken(), paid.orderId(), "refund-sandbox", body(500))
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    ResponseEntity<JsonNode> created =
        request(directToken(), paid.orderId(), "refund-first", body(500));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String refundId = created.getBody().get("refundId").asText();
    assertThat(created.getBody().get("state").asText()).isEqualTo("REQUESTED");
    assertThat(created.getBody().get("eligibleAmountMinor").asLong()).isEqualTo(1500);
    assertThat(created.getBody().get("replayed").asBoolean()).isFalse();

    ResponseEntity<JsonNode> replay =
        request(directToken(), paid.orderId(), "refund-first", body(500));
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getBody().get("refundId").asText()).isEqualTo(refundId);
    assertThat(replay.getBody().get("replayed").asBoolean()).isTrue();
    assertThat(request(directToken(), paid.orderId(), "refund-first", body(501)).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);

    assertThat(status(directToken(), refundId).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(status(otherToken(), refundId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    refunds.markProcessing(refundId);
    refunds.succeed(refundId);
    RefundResult second =
        refunds.request(
            USER, paid.orderId(), "refund-second", new RefundRequest(1000L, "AUD", null));
    refunds.markProcessing(second.refundId());
    refunds.succeed(second.refundId());
    assertThat(request(directToken(), paid.orderId(), "refund-over", body(1)).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(attemptRefunded(paid.attemptId())).isEqualTo(1500);
    assertThat(refundMovementCount(paid.orderId())).isEqualTo(2);
    assertThat(refundCount(paid.orderId())).isEqualTo(2);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO inventory_ledger
                      (movement_id, business_event_key, movement_type, order_id, product_id,
                       inventory_delta, activity_quota_delta, payment_amount_minor,
                       payment_currency)
                    VALUES (?, ?, 'STANDARD_PAYMENT', ?, ?, 0, 0, 1500, 'AUD')
                    """,
                    UUID.randomUUID().toString(),
                    "duplicate-payment:" + paid.attemptId(),
                    paid.orderId(),
                    paid.productId()))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(1062));

    String unpaid = seedStandardOrder(USER, 900, "unpaid");
    assertThat(request(directToken(), unpaid, "refund-unpaid", body(100)).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            request(directToken(), UUID.randomUUID().toString(), "refund-unknown", body(100))
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(refundCount(unpaid)).isZero();
  }

  @Test
  void legalDuplicateReorderedAndFailedTransitionsKeepOneDurableResult() {
    PaidFixture successPaid = seedPaidStandard(900, "transition-success");
    RefundResult success =
        refunds.request(
            USER,
            successPaid.orderId(),
            "refund-transition-success",
            new RefundRequest(400L, "AUD", null));
    assertThatThrownBy(() -> refunds.succeed(success.refundId()))
        .isInstanceOfSatisfying(
            RefundException.class, exception -> assertThat(exception.status()).isEqualTo(409));
    assertThat(refunds.markProcessing(success.refundId()).state()).isEqualTo("PROCESSING");
    assertThat(refunds.markProcessing(success.refundId()).replayed()).isTrue();
    assertThat(refunds.succeed(success.refundId()).state()).isEqualTo("SUCCEEDED");
    assertThat(refunds.succeed(success.refundId()).replayed()).isTrue();
    assertThat(refunds.markProcessing(success.refundId()).state()).isEqualTo("SUCCEEDED");
    assertThatThrownBy(() -> refunds.fail(success.refundId(), "DECLINED"))
        .isInstanceOfSatisfying(
            RefundException.class, exception -> assertThat(exception.status()).isEqualTo(409));
    assertThat(refundMovementCount(successPaid.orderId())).isOne();
    assertThat(refundOutboxCount(success.refundId())).isEqualTo(3);

    PaidFixture failedPaid = seedPaidStandard(700, "transition-failed");
    RefundResult failed =
        refunds.request(
            USER,
            failedPaid.orderId(),
            "refund-transition-failed",
            new RefundRequest(700L, "AUD", null));
    refunds.markProcessing(failed.refundId());
    assertThat(refunds.fail(failed.refundId(), "PROVIDER_DECLINED").state()).isEqualTo("FAILED");
    assertThat(refunds.fail(failed.refundId(), "PROVIDER_DECLINED").replayed()).isTrue();
    assertThatThrownBy(() -> refunds.fail(failed.refundId(), "OTHER_DECLINE"))
        .isInstanceOfSatisfying(
            RefundException.class, exception -> assertThat(exception.status()).isEqualTo(409));
    assertThatThrownBy(() -> refunds.succeed(failed.refundId()))
        .isInstanceOfSatisfying(
            RefundException.class, exception -> assertThat(exception.status()).isEqualTo(409));
    assertThat(refundMovementCount(failedPaid.orderId())).isZero();
    assertThat(attemptRefunded(failedPaid.attemptId())).isZero();
    assertThat(refundOutboxCount(failed.refundId())).isEqualTo(3);

    RefundResult replacement =
        refunds.request(
            USER,
            failedPaid.orderId(),
            "refund-after-failure",
            new RefundRequest(700L, "AUD", null));
    assertThat(replacement.state()).isEqualTo("REQUESTED");
  }

  @Test
  void transactionEndFailureRollsBackRefundLedgerAggregateAndOutboxThenReplaySucceeds() {
    PaidFixture paid = seedPaidStandard(800, "rollback");
    RefundResult requested =
        refunds.request(
            USER, paid.orderId(), "refund-rollback", new RefundRequest(800L, "AUD", null));
    refunds.markProcessing(requested.refundId());
    long outboxBefore = refundOutboxCount(requested.refundId());

    RefundRepository failAtOutbox =
        new RefundRepository(jdbc, objectMapper) {
          @Override
          public void insertOutbox(RefundRecord refund, String eventType, long version) {
            if ("REFUND_SUCCEEDED".equals(eventType)) {
              throw new IllegalStateException("controlled failure after refund ledger");
            }
            super.insertOutbox(refund, eventType, version);
          }
        };
    RefundService failing = service(failAtOutbox);
    assertThatThrownBy(() -> failing.succeed(requested.refundId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled failure after refund ledger");
    assertThat(refundState(requested.refundId())).containsExactly("PROCESSING", "2", "0");
    assertThat(attemptRefunded(paid.attemptId())).isZero();
    assertThat(refundMovementCount(paid.orderId())).isZero();
    assertThat(refundOutboxCount(requested.refundId())).isEqualTo(outboxBefore);

    assertThat(refunds.succeed(requested.refundId()).state()).isEqualTo("SUCCEEDED");
    assertThat(attemptRefunded(paid.attemptId())).isEqualTo(800);
    assertThat(refundMovementCount(paid.orderId())).isOne();
    assertThat(refundOutboxCount(requested.refundId())).isEqualTo(outboxBefore + 1);

    PaidFixture requestPaid = seedPaidStandard(300, "request-rollback");
    RefundRepository failRequestOutbox =
        new RefundRepository(jdbc, objectMapper) {
          @Override
          public void insertOutbox(RefundRecord refund, String eventType, long version) {
            if ("REFUND_REQUESTED".equals(eventType)) {
              throw new IllegalStateException("controlled failure after refund insert");
            }
            super.insertOutbox(refund, eventType, version);
          }
        };
    assertThatThrownBy(
            () ->
                service(failRequestOutbox)
                    .request(
                        USER,
                        requestPaid.orderId(),
                        "refund-request-rollback",
                        new RefundRequest(300L, "AUD", null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled failure after refund insert");
    assertThat(refundCount(requestPaid.orderId())).isZero();
    assertThat(
            refunds
                .request(
                    USER,
                    requestPaid.orderId(),
                    "refund-request-rollback",
                    new RefundRequest(300L, "AUD", null))
                .state())
        .isEqualTo("REQUESTED");
  }

  @Test
  void concurrentReservationsSerializeOnPaymentAndNeverOverRefund() throws Exception {
    for (int iteration = 0; iteration < 10; iteration++) {
      int current = iteration;
      PaidFixture paid = seedPaidStandard(1000 + current, "concurrent-" + current);
      long requested = 700 + current;
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch release = new CountDownLatch(1);
      CompletableFuture<Object> first =
          CompletableFuture.supplyAsync(
              () ->
                  concurrentRequest(ready, release, paid.orderId(), "first-" + current, requested));
      CompletableFuture<Object> second =
          CompletableFuture.supplyAsync(
              () ->
                  concurrentRequest(
                      ready, release, paid.orderId(), "second-" + current, requested));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      List<Object> outcomes =
          List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertThat(outcomes.stream().filter(RefundResult.class::isInstance)).hasSize(1);
      assertThat(outcomes.stream().filter(RefundException.class::isInstance)).hasSize(1);
      assertThat(
              ((RefundException)
                      outcomes.stream()
                          .filter(RefundException.class::isInstance)
                          .findFirst()
                          .orElseThrow())
                  .status())
          .isEqualTo(409);
      assertThat(refundCount(paid.orderId())).isOne();
      assertThat(refundMovementCount(paid.orderId())).isZero();
    }
  }

  @Test
  void reconciliationConvergesOnlyDerivedAggregateAndReportsContradictionsWithoutWrites() {
    PaidFixture paid = seedPaidStandard(1200, "reconcile");
    RefundResult refund =
        refunds.request(
            USER, paid.orderId(), "refund-reconcile", new RefundRequest(500L, "AUD", null));
    refunds.markProcessing(refund.refundId());
    refunds.succeed(refund.refundId());

    assertThat(refunds.reconcile(refund.refundId()).outcome())
        .isEqualTo(RefundReconciliationResult.Outcome.CONSISTENT);
    jdbc.update(
        "UPDATE mock_payment_attempt SET refunded_amount_minor = 0 WHERE attempt_id = ?",
        paid.attemptId());
    assertThat(refunds.reconcile(refund.refundId()).outcome())
        .isEqualTo(RefundReconciliationResult.Outcome.CONVERGED);
    assertThat(attemptRefunded(paid.attemptId())).isEqualTo(500);
    assertThat(refunds.reconcile(refund.refundId()).outcome())
        .isEqualTo(RefundReconciliationResult.Outcome.CONSISTENT);

    jdbc.update(
        """
        INSERT INTO inventory_ledger
          (movement_id, business_event_key, movement_type, order_id, product_id,
           inventory_delta, activity_quota_delta, payment_amount_minor, payment_currency)
        VALUES (?, ?, 'STANDARD_REFUND', ?, ?, 0, 0, 1, 'AUD')
        """,
        UUID.randomUUID().toString(),
        "orphan-refund:" + refund.refundId(),
        paid.orderId(),
        paid.productId());
    long outboxBefore = refundOutboxCount(refund.refundId());
    RefundReconciliationResult contradiction = refunds.reconcile(refund.refundId());
    assertThat(contradiction.outcome()).isEqualTo(RefundReconciliationResult.Outcome.CONTRADICTION);
    assertThat(contradiction.contradictions()).contains("REFUND_LEDGER_SET_MISMATCH");
    assertThat(attemptRefunded(paid.attemptId())).isEqualTo(500);
    assertThat(refundOutboxCount(refund.refundId())).isEqualTo(outboxBefore);
  }

  @Test
  void reconciliationReportsMissingCallbackAndLateTimeoutNeverRestoresRefundedSeckill() {
    RawRefund raw = seedRawRefundWithoutCallback();
    RefundReconciliationResult missingCallback = refunds.reconcile(raw.refundId());
    assertThat(missingCallback.outcome())
        .isEqualTo(RefundReconciliationResult.Outcome.CONTRADICTION);
    assertThat(missingCallback.contradictions()).contains("PAYMENT_CALLBACK_MISSING");
    assertThat(attemptRefunded(raw.attemptId())).isZero();

    SeckillFixture seckill = seedPaidSeckill("refund-timeout", 1400);
    long stockBefore = productStock(seckill.productId());
    long projectionBefore = activityProjection(seckill.activityId());
    RefundResult refund =
        refunds.request(
            USER,
            seckill.orderId(),
            "refund-seckill-timeout",
            new RefundRequest(1400L, "AUD", null));
    refunds.markProcessing(refund.refundId());
    refunds.succeed(refund.refundId());

    assertThat(cancellations().cancel(seckill.timeout()).outcome())
        .isEqualTo(SeckillCancellationService.Outcome.FINAL_PRESERVED);
    assertThat(orderStatus(seckill.orderId())).isEqualTo("PAID");
    assertThat(movementCount(seckill.orderId(), "SECKILL_PAYMENT")).isOne();
    assertThat(movementCount(seckill.orderId(), "SECKILL_REFUND")).isOne();
    assertThat(movementCount(seckill.orderId(), "SECKILL_UNPAID_CANCEL")).isZero();
    assertThat(productStock(seckill.productId())).isEqualTo(stockBefore);
    assertThat(activityProjection(seckill.activityId())).isEqualTo(projectionBefore);
    assertThat(refunds.reconcile(refund.refundId()).outcome())
        .isEqualTo(RefundReconciliationResult.Outcome.CONSISTENT);
  }

  @Test
  void databaseRejectsInvalidRefundAmountsAndStateShapes() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO mock_refund
                      (refund_id, user_subject, order_id, order_kind, payment_attempt_id,
                       request_idempotency_key, intent_hash, eligible_amount_minor,
                       requested_amount_minor, currency)
                    VALUES (?, 'invalid', ?, 'STANDARD', ?, 'invalid-over', REPEAT('0', 64),
                            100, 101, 'AUD')
                    """,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString()))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(3819));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO mock_refund
                      (refund_id, user_subject, order_id, order_kind, payment_attempt_id,
                       request_idempotency_key, intent_hash, eligible_amount_minor,
                       requested_amount_minor, currency, state, state_version)
                    VALUES (?, 'invalid', ?, 'STANDARD', ?, 'invalid-state', REPEAT('0', 64),
                            100, 100, 'AUD', 'SUCCEEDED', 3)
                    """,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString()))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(3819));
  }

  private PaidFixture seedPaidStandard(long amount, String suffix) {
    String orderId = seedStandardOrder(USER, amount, suffix);
    String productId = "refund-product-" + suffix;
    MockPaymentResult attempt =
        payments.start(
            USER, orderId, "payment-refund-" + suffix, new MockPaymentRequest(amount, "AUD", null));
    payments.callback(
        "callback-refund-" + suffix,
        new MockPaymentCallbackRequest(
            UUID.randomUUID().toString(),
            attempt.callbackCorrelationId(),
            orderId,
            amount,
            "AUD",
            "SUCCEEDED"));
    return new PaidFixture(orderId, attempt.attemptId(), productId);
  }

  private String seedStandardOrder(String user, long amount, String suffix) {
    String orderId = UUID.randomUUID().toString();
    jdbc.update(
        """
        INSERT INTO standard_order
          (order_id, user_subject, product_id, product_name, unit_price_minor, currency,
           quantity, total_price_minor, product_version)
        VALUES (?, ?, ?, 'Refund fixture', ?, 'AUD', 1, ?, 1)
        """,
        orderId,
        user,
        "refund-product-" + suffix,
        amount,
        amount);
    return orderId;
  }

  private SeckillFixture seedPaidSeckill(String suffix, long amount) {
    String orderId = UUID.randomUUID().toString();
    String reservationId = UUID.randomUUID().toString();
    String transactionId = UUID.randomUUID().toString();
    String timeoutId = UUID.randomUUID().toString();
    String productId = "refund-product-" + suffix;
    String activityId = "refund-activity-" + suffix;
    Instant deadline = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
    jdbc.update(
        """
        INSERT INTO product
          (product_id, name, description, price_minor, currency, stock_quantity, available,
           publication_state, publication_version)
        VALUES (?, 'Refund fixture', '', ?, 'AUD', 9, TRUE, 'PUBLISHED', 1)
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
        "refund-reservation-" + suffix,
        orderId,
        java.sql.Timestamp.from(deadline));
    jdbc.update(
        """
        INSERT INTO seckill_order
          (order_id, reservation_id, transaction_event_id, timeout_event_id, user_subject,
           activity_id, product_id, product_name, unit_price_minor, currency, quantity,
           total_price_minor, unpaid_deadline, timeout_dispatch_state,
           timeout_broker_message_id, timeout_dispatched_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'Refund fixture', ?, 'AUD', 1, ?, ?, 'SENT',
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
        "refund-order-create:" + transactionId,
        orderId,
        reservationId,
        activityId,
        productId);
    MockPaymentResult attempt =
        payments.start(
            USER, orderId, "payment-refund-" + suffix, new MockPaymentRequest(amount, "AUD", null));
    payments.callback(
        "callback-refund-" + suffix,
        new MockPaymentCallbackRequest(
            UUID.randomUUID().toString(),
            attempt.callbackCorrelationId(),
            orderId,
            amount,
            "AUD",
            "SUCCEEDED"));
    return new SeckillFixture(
        orderId,
        productId,
        activityId,
        new SeckillTimeoutMessage(
            timeoutId, orderId, reservationId, "UNPAID", 1, deadline, transactionId));
  }

  private RawRefund seedRawRefundWithoutCallback() {
    String orderId = UUID.randomUUID().toString();
    String attemptId = UUID.randomUUID().toString();
    String refundId = UUID.randomUUID().toString();
    String productId = "refund-product-missing-callback";
    jdbc.update(
        """
        INSERT INTO standard_order
          (order_id, user_subject, product_id, product_name, unit_price_minor, currency,
           quantity, total_price_minor, product_version, status, state_version)
        VALUES (?, ?, ?, 'Refund fixture', 600, 'AUD', 1, 600, 1, 'PAID', 2)
        """,
        orderId,
        USER,
        productId);
    jdbc.update(
        """
        INSERT INTO mock_payment_attempt
          (attempt_id, callback_correlation_id, user_subject, order_id, order_kind,
           request_idempotency_key, intent_hash, amount_minor, currency, state,
           state_version, succeeded_at)
        VALUES (?, ?, ?, ?, 'STANDARD', ?, REPEAT('0', 64), 600, 'AUD', 'SUCCEEDED', 2,
                CURRENT_TIMESTAMP(6))
        """,
        attemptId,
        UUID.randomUUID().toString(),
        USER,
        orderId,
        "payment-missing-callback-" + refundId);
    jdbc.update(
        """
        INSERT INTO inventory_ledger
          (movement_id, business_event_key, movement_type, order_id, product_id,
           inventory_delta, activity_quota_delta, payment_amount_minor, payment_currency)
        VALUES (?, ?, 'STANDARD_PAYMENT', ?, ?, 0, 0, 600, 'AUD')
        """,
        UUID.randomUUID().toString(),
        "mock-payment:" + attemptId,
        orderId,
        productId);
    jdbc.update(
        """
        INSERT INTO mock_refund
          (refund_id, user_subject, order_id, order_kind, payment_attempt_id,
           request_idempotency_key, intent_hash, eligible_amount_minor,
           requested_amount_minor, currency)
        VALUES (?, ?, ?, 'STANDARD', ?, ?, REPEAT('0', 64), 600, 100, 'AUD')
        """,
        refundId,
        USER,
        orderId,
        attemptId,
        "refund-missing-callback-" + refundId);
    return new RawRefund(refundId, attemptId);
  }

  private RefundService service(RefundRepository repository) {
    return new RefundService(
        repository, new MockPaymentRepository(jdbc), transactionTemplate(), Clock.systemUTC());
  }

  private TransactionTemplate transactionTemplate() {
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transactions;
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

  private Object concurrentRequest(
      CountDownLatch ready,
      CountDownLatch release,
      String orderId,
      String idempotencyKey,
      long amount) {
    ready.countDown();
    try {
      if (!release.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Refund race was not released");
      }
      return refunds.request(
          USER,
          orderId,
          "refund-concurrent-" + idempotencyKey,
          new RefundRequest(amount, "AUD", null));
    } catch (RefundException exception) {
      return exception;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Refund race was interrupted", exception);
    }
  }

  private ResponseEntity<JsonNode> request(
      String token, String orderId, String idempotencyKey, Map<String, Object> body) {
    return request(token, orderId, idempotencyKey, body, false);
  }

  private ResponseEntity<JsonNode> requestWithSandbox(
      String token, String orderId, String idempotencyKey, Map<String, Object> body) {
    return request(token, orderId, idempotencyKey, body, true);
  }

  private ResponseEntity<JsonNode> request(
      String token,
      String orderId,
      String idempotencyKey,
      Map<String, Object> body,
      boolean sandbox) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    if (sandbox) {
      headers.set("X-Eval-Sandbox-Id", "not-enabled");
    }
    return http.exchange(
        "/api/orders/" + orderId + "/refunds",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        JsonNode.class);
  }

  private ResponseEntity<JsonNode> status(String token, String refundId) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return http.exchange(
        "/api/refunds/" + refundId, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
  }

  private long attemptRefunded(String attemptId) {
    return jdbc.queryForObject(
        "SELECT refunded_amount_minor FROM mock_payment_attempt WHERE attempt_id = ?",
        Long.class,
        attemptId);
  }

  private List<String> refundState(String refundId) {
    return jdbc.queryForObject(
        """
        SELECT state, state_version, refunded_amount_minor
        FROM mock_refund WHERE refund_id = ?
        """,
        (row, index) -> List.of(row.getString(1), row.getString(2), row.getString(3)),
        refundId);
  }

  private long refundMovementCount(String orderId) {
    Long count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM inventory_ledger
            WHERE order_id = ? AND movement_type IN ('STANDARD_REFUND', 'SECKILL_REFUND')
            """,
            Long.class,
            orderId);
    return count == null ? 0 : count;
  }

  private long refundCount(String orderId) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM mock_refund WHERE order_id = ?", Long.class, orderId);
    return count == null ? 0 : count;
  }

  private long refundOutboxCount(String refundId) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type = 'REFUND' "
                + "AND aggregate_id = ?",
            Long.class,
            refundId);
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

  private long productStock(String productId) {
    return jdbc.queryForObject(
        "SELECT stock_quantity FROM product WHERE product_id = ?", Long.class, productId);
  }

  private long activityProjection(String activityId) {
    return jdbc.queryForObject(
        "SELECT projection_version FROM seckill_activity WHERE activity_id = ?",
        Long.class,
        activityId);
  }

  private String orderStatus(String orderId) {
    return jdbc.queryForObject(
        "SELECT status FROM seckill_order WHERE order_id = ?", String.class, orderId);
  }

  private static Map<String, Object> body(long amountMinor) {
    return Map.of("amountMinor", amountMinor, "currency", "AUD");
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

  private static String signedTestToken(String issuer, String audience, String tokenType)
      throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(USER)
            .claim("token_type", tokenType)
            .claim("principal_state", "ACTIVE")
            .claim("permissions", List.of("refund:create"))
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

  private record PaidFixture(String orderId, String attemptId, String productId) {}

  private record RawRefund(String refundId, String attemptId) {}

  private record SeckillFixture(
      String orderId, String productId, String activityId, SeckillTimeoutMessage timeout) {}
}
