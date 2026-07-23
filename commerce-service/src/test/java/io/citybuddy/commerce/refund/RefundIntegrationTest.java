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
import io.citybuddy.commerce.action.ActionException;
import io.citybuddy.commerce.action.ActionProperties;
import io.citybuddy.commerce.action.ActionReceiptView;
import io.citybuddy.commerce.action.ActionRepository;
import io.citybuddy.commerce.action.ActionRequestContext;
import io.citybuddy.commerce.action.ActionService;
import io.citybuddy.commerce.action.PendingActionView;
import io.citybuddy.commerce.action.PrepareActionCommand;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
    registry.add("citybuddy.obo.enabled", () -> "true");
    registry.add("citybuddy.obo.issuer", () -> "https://identity.citybuddy.test");
    registry.add("citybuddy.obo.jwks-url", () -> required("IDENTITY_JWKS_URL"));
    registry.add("citybuddy.obo.clock-skew", () -> "30s");
    registry.add("citybuddy.obo.jwks-cache-ttl", () -> "30s");
    registry.add("citybuddy.actions.enabled", () -> "true");
    registry.add("citybuddy.actions.required-scope", () -> "refund:create");
    registry.add("citybuddy.actions.pending-ttl", () -> "15m");
    registry.add("citybuddy.actions.maximum-concurrency-attempts", () -> "3");
    registry.add("citybuddy.actions.lock-wait-timeout-seconds", () -> "1");
  }

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MockPaymentService payments;
  @Autowired private RefundService refunds;
  @Autowired private ActionService actions;
  @Autowired private ActionRepository actionRepository;

  @Autowired
  private ObjectProvider<io.citybuddy.commerce.evaluation.EvaluationSandboxAccess> sandboxAccess;

  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void pendingActionPrepareConfirmReplayAndClosedOboBoundaryStayAtomic() throws Exception {
    PaidFixture paid = seedPaidStandard(1300, "action-main");
    String turnId = UUID.randomUUID().toString();
    String traceId = "action-trace-main";
    String token = signedOboToken(USER, "action-session", "refund:create", null);

    ResponseEntity<JsonNode> prepared =
        prepareAction(
            token,
            "action-session",
            traceId,
            turnId,
            null,
            Map.of(
                "actionType",
                "REFUND_REQUEST",
                "arguments",
                Map.of("orderId", paid.orderId(), "amountMinor", 500, "currency", "AUD")));
    assertThat(prepared.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String pendingActionId = prepared.getBody().get("pendingActionId").asText();
    assertThat(prepared.getBody().get("state").asText()).isEqualTo("PREPARED");
    assertThat(prepared.getBody().get("replayed").asBoolean()).isFalse();

    ResponseEntity<JsonNode> prepareReplay =
        prepareAction(
            token,
            "action-session",
            traceId,
            turnId,
            null,
            Map.of(
                "actionType",
                "REFUND_REQUEST",
                "arguments",
                Map.of("orderId", paid.orderId(), "amountMinor", 500, "currency", "AUD")));
    assertThat(prepareReplay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(prepareReplay.getBody().get("pendingActionId").asText()).isEqualTo(pendingActionId);
    assertThat(prepareReplay.getBody().get("replayed").asBoolean()).isTrue();
    assertThat(
            prepareAction(
                    token,
                    "action-session",
                    traceId,
                    turnId,
                    null,
                    Map.of(
                        "actionType",
                        "REFUND_REQUEST",
                        "arguments",
                        Map.of("orderId", paid.orderId(), "amountMinor", 501, "currency", "AUD")))
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            prepareAction(
                    token,
                    "action-session",
                    traceId,
                    UUID.randomUUID().toString(),
                    null,
                    Map.of(
                        "actionType",
                        "REFUND_REQUEST",
                        "arguments",
                        Map.of(
                            "orderId",
                            paid.orderId(),
                            "amountMinor",
                            500,
                            "currency",
                            "AUD",
                            "userSubject",
                            OTHER_USER)))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            prepareAction(
                    directToken(),
                    "action-session",
                    traceId,
                    UUID.randomUUID().toString(),
                    null,
                    Map.of(
                        "actionType",
                        "REFUND_REQUEST",
                        "arguments",
                        Map.of("orderId", paid.orderId(), "amountMinor", 500, "currency", "AUD")))
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            prepareAction(
                    signedOboToken(USER, "action-session", "catalog:read", null),
                    "action-session",
                    traceId,
                    UUID.randomUUID().toString(),
                    null,
                    Map.of(
                        "actionType",
                        "REFUND_REQUEST",
                        "arguments",
                        Map.of("orderId", paid.orderId(), "amountMinor", 500, "currency", "AUD")))
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            prepareAction(
                    signedOboToken(USER, "different-session", "refund:create", null),
                    "action-session",
                    traceId,
                    UUID.randomUUID().toString(),
                    null,
                    Map.of(
                        "actionType",
                        "REFUND_REQUEST",
                        "arguments",
                        Map.of("orderId", paid.orderId(), "amountMinor", 500, "currency", "AUD")))
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            prepareAction(
                    signedOboToken(OTHER_USER, "action-other-session", "refund:create", null),
                    "action-other-session",
                    traceId,
                    UUID.randomUUID().toString(),
                    null,
                    Map.of(
                        "actionType",
                        "REFUND_REQUEST",
                        "arguments",
                        Map.of("orderId", paid.orderId(), "amountMinor", 500, "currency", "AUD")))
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            prepareAction(
                    token,
                    "action-session",
                    traceId,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    Map.of(
                        "actionType",
                        "REFUND_REQUEST",
                        "arguments",
                        Map.of("orderId", paid.orderId(), "amountMinor", 500, "currency", "AUD")))
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            confirmAction(
                    token,
                    "action-session",
                    traceId,
                    UUID.randomUUID().toString(),
                    null,
                    pendingActionId,
                    null)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    String otherSessionToken = signedOboToken(USER, "action-other-session", "refund:create", null);
    ResponseEntity<JsonNode> crossSession =
        confirmAction(
            otherSessionToken,
            "action-other-session",
            traceId,
            turnId,
            null,
            pendingActionId,
            null);
    ResponseEntity<JsonNode> unknownInOtherSession =
        confirmAction(
            otherSessionToken,
            "action-other-session",
            traceId,
            turnId,
            null,
            UUID.randomUUID().toString(),
            null);
    assertThat(crossSession.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(unknownInOtherSession.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(crossSession.getBody()).isEqualTo(unknownInOtherSession.getBody());
    assertThat(refundCount(paid.orderId())).isZero();

    ResponseEntity<JsonNode> confirmed =
        confirmAction(token, "action-session", traceId, turnId, null, pendingActionId, null);
    assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
    String receiptId = confirmed.getBody().get("receiptId").asText();
    String refundId = confirmed.getBody().get("refundId").asText();
    assertThat(confirmed.getBody().get("status").asText()).isEqualTo("REQUESTED");
    assertThat(confirmed.getBody().get("replayed").asBoolean()).isFalse();
    assertThat(refundCount(paid.orderId())).isOne();
    assertThat(refundOutboxCount(refundId)).isOne();
    assertThat(actionReceiptCount(pendingActionId)).isOne();

    ResponseEntity<JsonNode> confirmReplay =
        confirmAction(token, "action-session", traceId, turnId, null, pendingActionId, null);
    assertThat(confirmReplay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(confirmReplay.getBody().get("receiptId").asText()).isEqualTo(receiptId);
    assertThat(confirmReplay.getBody().get("refundId").asText()).isEqualTo(refundId);
    assertThat(confirmReplay.getBody().get("replayed").asBoolean()).isTrue();
    assertThat(refundCount(paid.orderId())).isOne();
    assertThat(refundOutboxCount(refundId)).isOne();
    assertThat(actionReceiptCount(pendingActionId)).isOne();

    refunds.markProcessing(refundId);
    refunds.succeed(refundId);
    ResponseEntity<JsonNode> replayAfterRefundCompletion =
        confirmAction(token, "action-session", traceId, turnId, null, pendingActionId, null);
    assertThat(replayAfterRefundCompletion.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replayAfterRefundCompletion.getBody().get("receiptId").asText())
        .isEqualTo(receiptId);
    assertThat(replayAfterRefundCompletion.getBody().get("replayed").asBoolean()).isTrue();
    assertThat(refundCount(paid.orderId())).isOne();
    assertThat(refundOutboxCount(refundId)).isEqualTo(3);
    assertThat(actionReceiptCount(pendingActionId)).isOne();
  }

  @Test
  void actionTransactionRollsBackRefundOutboxConsumeAndReceiptAtFinalWriteBoundary() {
    PaidFixture paid = seedPaidStandard(900, "action-rollback");
    String turnId = UUID.randomUUID().toString();
    ActionRequestContext context =
        new ActionRequestContext(
            USER,
            "action-rollback-session",
            "action-rollback-trace",
            turnId,
            null,
            "refund:create");
    PendingActionView pending =
        actions.prepare(
            context, new PrepareActionCommand("REFUND_REQUEST", paid.orderId(), 400L, "AUD"));
    ActionRepository failReceipt =
        new ActionRepository(jdbc, objectMapper) {
          @Override
          public void insertReceipt(ActionReceiptRecord receipt) {
            throw new IllegalStateException("controlled failure before receipt insert");
          }
        };
    ActionService failing =
        new ActionService(
            failReceipt,
            refunds,
            transactionTemplate(),
            new ActionProperties("refund:create", java.time.Duration.ofMinutes(15), 3, 1),
            Clock.systemUTC(),
            sandboxAccess);

    assertThatThrownBy(() -> failing.confirm(context, pending.pendingActionId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled failure before receipt insert");
    assertThat(pendingState(pending.pendingActionId())).containsExactly("PREPARED", "1", null);
    assertThat(refundCount(paid.orderId())).isZero();
    assertThat(actionReceiptCount(pending.pendingActionId())).isZero();

    ActionReceiptView receipt = actions.confirm(context, pending.pendingActionId());
    assertThat(receipt.status()).isEqualTo("REQUESTED");
    assertThat(refundCount(paid.orderId())).isOne();
    assertThat(refundOutboxCount(receipt.refundId())).isOne();
    assertThat(pendingState(pending.pendingActionId()).subList(0, 2))
        .containsExactly("CONSUMED", "2");
    assertThat(actionReceiptCount(pending.pendingActionId())).isOne();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE action_receipt SET result_hash = REPEAT('0', 64) WHERE receipt_id = ?",
                    receipt.receiptId()))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(1142));
  }

  @Test
  void concurrentIdenticalConfirmsConvergeOnOneReceiptAndOneRefund() throws Exception {
    PaidFixture paid = seedPaidStandard(1000, "action-concurrent");
    String turnId = UUID.randomUUID().toString();
    ActionRequestContext context =
        new ActionRequestContext(
            USER,
            "action-concurrent-session",
            "action-concurrent-trace",
            turnId,
            null,
            "refund:create");
    PendingActionView pending =
        actions.prepare(
            context, new PrepareActionCommand("REFUND_REQUEST", paid.orderId(), 700L, "AUD"));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<ActionReceiptView> first =
        CompletableFuture.supplyAsync(
            () -> concurrentConfirm(ready, release, context, pending.pendingActionId()));
    CompletableFuture<ActionReceiptView> second =
        CompletableFuture.supplyAsync(
            () -> concurrentConfirm(ready, release, context, pending.pendingActionId()));
    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    release.countDown();
    ActionReceiptView left = first.get(10, TimeUnit.SECONDS);
    ActionReceiptView right = second.get(10, TimeUnit.SECONDS);
    assertThat(left.receiptId()).isEqualTo(right.receiptId());
    assertThat(left.refundId()).isEqualTo(right.refundId());
    assertThat(refundCount(paid.orderId())).isOne();
    assertThat(refundOutboxCount(left.refundId())).isOne();
    assertThat(actionReceiptCount(pending.pendingActionId())).isOne();
  }

  @Test
  void receiptReplayRejectsEveryMutableContentCarrierAndMissingOutbox() {
    PaidFixture paid = seedPaidStandard(1100, "action-integrity");
    String turnId = UUID.randomUUID().toString();
    ActionRequestContext context =
        new ActionRequestContext(
            USER,
            "action-integrity-session",
            "action-integrity-trace",
            turnId,
            null,
            "refund:create");
    PendingActionView pending =
        actions.prepare(
            context, new PrepareActionCommand("REFUND_REQUEST", paid.orderId(), 600L, "AUD"));
    ActionReceiptView receipt = actions.confirm(context, pending.pendingActionId());
    JdbcTemplate corruption = rootJdbc();
    Map<String, Object> original =
        corruption.queryForMap(
            """
            SELECT receipt_id, receipt_idempotency_key, pending_action_id, action_type,
                   argument_hash, result_hash, user_subject, support_session_id, trace_id,
                   turn_id, sandbox_id, order_id, payment_attempt_id, refund_id,
                   resulting_resource_version, result_state, amount_minor, currency,
                   outbox_event_id, outbox_created_at, committed_at
            FROM action_receipt WHERE receipt_id = ?
            """,
            receipt.receiptId());
    List<ColumnCorruption> corruptions =
        List.of(
            new ColumnCorruption("receipt_id", UUID.randomUUID().toString()),
            new ColumnCorruption("receipt_idempotency_key", "f".repeat(64)),
            new ColumnCorruption("pending_action_id", UUID.randomUUID().toString()),
            new ColumnCorruption("argument_hash", "e".repeat(64)),
            new ColumnCorruption("result_hash", "d".repeat(64)),
            new ColumnCorruption("user_subject", "other-user"),
            new ColumnCorruption("support_session_id", "other-session"),
            new ColumnCorruption("trace_id", "other-trace"),
            new ColumnCorruption("turn_id", UUID.randomUUID().toString()),
            new ColumnCorruption("sandbox_id", UUID.randomUUID().toString()),
            new ColumnCorruption("order_id", UUID.randomUUID().toString()),
            new ColumnCorruption("payment_attempt_id", UUID.randomUUID().toString()),
            new ColumnCorruption("refund_id", UUID.randomUUID().toString()),
            new ColumnCorruption("amount_minor", 601L),
            new ColumnCorruption("currency", "CNY"),
            new ColumnCorruption("outbox_event_id", UUID.randomUUID().toString()),
            new ColumnCorruption(
                "outbox_created_at",
                java.sql.Timestamp.from(receipt.committedAt().plus(1, ChronoUnit.MICROS))),
            new ColumnCorruption(
                "committed_at",
                java.sql.Timestamp.from(receipt.committedAt().plus(1, ChronoUnit.MICROS))));
    String currentReceiptId = receipt.receiptId();
    for (ColumnCorruption fault : corruptions) {
      corruption.update(
          "UPDATE action_receipt SET " + fault.column() + " = ? WHERE receipt_id = ?",
          fault.value(),
          currentReceiptId);
      if ("receipt_id".equals(fault.column())) {
        currentReceiptId = fault.value().toString();
      }
      assertThatThrownBy(() -> actions.confirm(context, pending.pendingActionId()))
          .isInstanceOfAny(
              ActionException.class,
              ActionRepository.ActionIntegrityException.class,
              RefundException.class);
      corruption.update(
          "UPDATE action_receipt SET " + fault.column() + " = ? WHERE receipt_id = ?",
          original.get(fault.column()),
          currentReceiptId);
      currentReceiptId = receipt.receiptId();
    }

    Map<String, Object> outbox =
        corruption.queryForMap(
            "SELECT * FROM commerce_outbox WHERE event_id = ?",
            receiptOutboxId(receipt.receiptId()));
    List<ColumnCorruption> outboxCorruptions =
        List.of(
            new ColumnCorruption("event_id", UUID.randomUUID().toString()),
            new ColumnCorruption("aggregate_type", "ORDER"),
            new ColumnCorruption("aggregate_id", UUID.randomUUID().toString()),
            new ColumnCorruption("aggregate_version", 2L),
            new ColumnCorruption("event_type", "REFUND_FAILED"),
            new ColumnCorruption("payload", "{}"),
            new ColumnCorruption("publication_state", "PUBLISHED"),
            new ColumnCorruption("publish_attempts", -1L),
            new ColumnCorruption(
                "created_at",
                java.sql.Timestamp.from(receipt.committedAt().plus(1, ChronoUnit.MICROS))),
            new ColumnCorruption(
                "published_at",
                java.sql.Timestamp.from(receipt.committedAt().plus(1, ChronoUnit.MICROS))));
    String currentOutboxId = receiptOutboxId(receipt.receiptId());
    for (ColumnCorruption fault : outboxCorruptions) {
      if ("publish_attempts".equals(fault.column())) {
        String constrainedOutboxId = currentOutboxId;
        assertThatThrownBy(
                () ->
                    corruption.update(
                        "UPDATE commerce_outbox SET publish_attempts = ? WHERE event_id = ?",
                        fault.value(),
                        constrainedOutboxId))
            .isInstanceOf(DataAccessException.class);
        continue;
      }
      corruption.update(
          "UPDATE commerce_outbox SET " + fault.column() + " = ? WHERE event_id = ?",
          fault.value(),
          currentOutboxId);
      if ("event_id".equals(fault.column())) {
        currentOutboxId = fault.value().toString();
      }
      assertThatThrownBy(() -> actions.confirm(context, pending.pendingActionId()))
          .isInstanceOfAny(
              ActionException.class,
              ActionRepository.ActionIntegrityException.class,
              RefundException.class);
      corruption.update(
          "UPDATE commerce_outbox SET " + fault.column() + " = ? WHERE event_id = ?",
          originalOutboxValue(outbox, fault.column()),
          currentOutboxId);
      currentOutboxId = receiptOutboxId(receipt.receiptId());
    }
    corruption.update(
        "DELETE FROM commerce_outbox WHERE event_id = ?", receiptOutboxId(receipt.receiptId()));
    assertThatThrownBy(() -> actions.confirm(context, pending.pendingActionId()))
        .isInstanceOfAny(
            ActionException.class,
            ActionRepository.ActionIntegrityException.class,
            RefundException.class);
    restoreOutbox(corruption, outbox);
    assertThat(actions.confirm(context, pending.pendingActionId()).receiptId())
        .isEqualTo(receipt.receiptId());

    corruption.update(
        """
        UPDATE commerce_outbox
        SET publication_state = 'PUBLISHED', publish_attempts = 0, published_at = created_at
        WHERE event_id = ?
        """,
        receiptOutboxId(receipt.receiptId()));
    assertThatThrownBy(() -> actions.confirm(context, pending.pendingActionId()))
        .isInstanceOf(ActionRepository.ActionIntegrityException.class);
    corruption.update(
        """
        UPDATE commerce_outbox
        SET publication_state = ?, publish_attempts = ?, published_at = ?
        WHERE event_id = ?
        """,
        originalOutboxValue(outbox, "publication_state"),
        originalOutboxValue(outbox, "publish_attempts"),
        originalOutboxValue(outbox, "published_at"),
        receiptOutboxId(receipt.receiptId()));
    assertThat(actions.confirm(context, pending.pendingActionId()).receiptId())
        .isEqualTo(receipt.receiptId());

    String duplicateOutboxId = UUID.randomUUID().toString();
    assertThatThrownBy(
            () ->
                corruption.update(
                    """
                    INSERT INTO commerce_outbox
                      (event_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                       payload, publication_state, publish_attempts, created_at, published_at)
                    SELECT ?, aggregate_type, aggregate_id, aggregate_version, event_type, payload,
                           publication_state, publish_attempts, created_at, published_at
                    FROM commerce_outbox WHERE event_id = ?
                    """,
                    duplicateOutboxId,
                    receiptOutboxId(receipt.receiptId())))
        .isInstanceOf(DataAccessException.class);
    assertThat(actions.confirm(context, pending.pendingActionId()).receiptId())
        .isEqualTo(receipt.receiptId());
  }

  @Test
  void preparedActionRejectsPerColumnContradictionsBeforeAnyRefundMutation() {
    PaidFixture paid = seedPaidStandard(1200, "action-pending-integrity");
    String turnId = UUID.randomUUID().toString();
    ActionRequestContext context =
        new ActionRequestContext(
            USER, "action-pending-session", "action-pending-trace", turnId, null, "refund:create");
    PendingActionView pending =
        actions.prepare(
            context, new PrepareActionCommand("REFUND_REQUEST", paid.orderId(), 650L, "AUD"));
    JdbcTemplate corruption = rootJdbc();
    Map<String, Object> original =
        corruption.queryForMap(
            "SELECT * FROM pending_action WHERE pending_action_id = ?", pending.pendingActionId());
    List<ColumnCorruption> corruptions =
        List.of(
            new ColumnCorruption("pending_action_id", UUID.randomUUID().toString()),
            new ColumnCorruption("action_idempotency_key", "f".repeat(64)),
            new ColumnCorruption("argument_hash", "e".repeat(64)),
            new ColumnCorruption("user_subject", OTHER_USER),
            new ColumnCorruption("support_session_id", "other-session"),
            new ColumnCorruption("trace_id", "other-trace"),
            new ColumnCorruption("turn_id", UUID.randomUUID().toString()),
            new ColumnCorruption("required_scope", "catalog:read"),
            new ColumnCorruption("sandbox_id", UUID.randomUUID().toString()),
            new ColumnCorruption("order_id", UUID.randomUUID().toString()),
            new ColumnCorruption("order_kind", "SECKILL"),
            new ColumnCorruption("payment_attempt_id", UUID.randomUUID().toString()),
            new ColumnCorruption("target_order_version", 3L),
            new ColumnCorruption("amount_minor", 651L),
            new ColumnCorruption("currency", "CNY"),
            new ColumnCorruption(
                "expires_at",
                java.sql.Timestamp.from(pending.expiresAt().plus(1, ChronoUnit.MICROS))),
            new ColumnCorruption(
                "created_at",
                java.sql.Timestamp.from(
                    ((java.sql.Timestamp) original.get("created_at"))
                        .toInstant()
                        .plus(1, ChronoUnit.MICROS))));
    String currentPendingId = pending.pendingActionId();
    for (ColumnCorruption fault : corruptions) {
      corruption.update(
          "UPDATE pending_action SET " + fault.column() + " = ? WHERE pending_action_id = ?",
          fault.value(),
          currentPendingId);
      if ("pending_action_id".equals(fault.column())) {
        currentPendingId = fault.value().toString();
      }
      assertThatThrownBy(() -> actions.confirm(context, pending.pendingActionId()))
          .isInstanceOfAny(
              ActionException.class,
              ActionRepository.ActionIntegrityException.class,
              RefundException.class);
      assertThat(refundCount(paid.orderId())).isZero();
      corruption.update(
          "UPDATE pending_action SET " + fault.column() + " = ? WHERE pending_action_id = ?",
          original.get(fault.column()),
          currentPendingId);
      currentPendingId = pending.pendingActionId();
    }

    corruption.update(
        """
        UPDATE pending_action
        SET state = 'CONSUMED', state_version = 2, consumed_at = CURRENT_TIMESTAMP(6)
        WHERE pending_action_id = ?
        """,
        pending.pendingActionId());
    assertThatThrownBy(() -> actions.confirm(context, pending.pendingActionId()))
        .isInstanceOf(ActionException.class);
    assertThat(refundCount(paid.orderId())).isZero();
    corruption.update(
        """
        UPDATE pending_action
        SET state = 'PREPARED', state_version = 1, consumed_at = NULL
        WHERE pending_action_id = ?
        """,
        pending.pendingActionId());
    assertThat(actions.confirm(context, pending.pendingActionId()).status()).isEqualTo("REQUESTED");
  }

  @Test
  void expiredPendingActionRejectsWithoutAnyRefundMutation() {
    PaidFixture paid = seedPaidStandard(800, "action-expiry");
    ActionRequestContext context =
        new ActionRequestContext(
            USER,
            "action-expiry-session",
            "action-expiry-trace",
            UUID.randomUUID().toString(),
            null,
            "refund:create");
    PendingActionView pending =
        actions.prepare(
            context, new PrepareActionCommand("REFUND_REQUEST", paid.orderId(), 300L, "AUD"));
    JdbcTemplate corruption = rootJdbc();
    corruption.update(
        """
        UPDATE pending_action
        SET created_at = created_at - INTERVAL 1 HOUR,
            expires_at = expires_at - INTERVAL 1 HOUR
        WHERE pending_action_id = ?
        """,
        pending.pendingActionId());

    assertThatThrownBy(() -> actions.confirm(context, pending.pendingActionId()))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.category()).isEqualTo("CONFLICT");
            });
    assertThat(refundCount(paid.orderId())).isZero();
    assertThat(actionReceiptCount(pending.pendingActionId())).isZero();
    assertThat(pendingState(pending.pendingActionId()).subList(0, 2))
        .containsExactly("PREPARED", "1");
  }

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
    long paymentMovementsBeforeReplay = paymentMovementCount(paid.orderId());
    var paymentReplay = payments.callback(paid.callbackKey(), paid.callback());
    assertThat(paymentReplay.state()).isEqualTo("SUCCEEDED");
    assertThat(paymentReplay.replayed()).isTrue();
    assertThat(paymentMovementCount(paid.orderId())).isEqualTo(paymentMovementsBeforeReplay);
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
          public OutboxIdentity insertOutbox(
              RefundRecord refund, String eventType, long version, Instant occurredAt) {
            if ("REFUND_SUCCEEDED".equals(eventType)) {
              throw new IllegalStateException("controlled failure after refund ledger");
            }
            return super.insertOutbox(refund, eventType, version, occurredAt);
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
          public OutboxIdentity insertOutbox(
              RefundRecord refund, String eventType, long version, Instant occurredAt) {
            if ("REFUND_REQUESTED".equals(eventType)) {
              throw new IllegalStateException("controlled failure after refund insert");
            }
            return super.insertOutbox(refund, eventType, version, occurredAt);
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
  void reconciliationUsesCurrentLedgerTruthAfterWaitingForConcurrentSuccess() throws Exception {
    PaidFixture paid = seedPaidStandard(900, "reconcile-lock-wait");
    RefundResult refund =
        refunds.request(
            USER,
            paid.orderId(),
            "refund-reconcile-lock-wait",
            new RefundRequest(900L, "AUD", null));
    refunds.markProcessing(refund.refundId());

    CountDownLatch successReadyToCommit = new CountDownLatch(1);
    CountDownLatch releaseSuccessCommit = new CountDownLatch(1);
    RefundRepository pausedSuccessRepository =
        new RefundRepository(jdbc, objectMapper) {
          @Override
          public OutboxIdentity insertOutbox(
              RefundRecord current, String eventType, long version, Instant occurredAt) {
            OutboxIdentity outbox = super.insertOutbox(current, eventType, version, occurredAt);
            if ("REFUND_SUCCEEDED".equals(eventType)) {
              successReadyToCommit.countDown();
              awaitLatch(releaseSuccessCommit, "Concurrent refund success was not released");
            }
            return outbox;
          }
        };

    CountDownLatch reconciliationReadOldRefund = new CountDownLatch(1);
    RefundRepository observedReconciliationRepository =
        new RefundRepository(jdbc, objectMapper) {
          @Override
          public Optional<RefundRecord> findById(String refundId) {
            Optional<RefundRecord> identified = super.findById(refundId);
            reconciliationReadOldRefund.countDown();
            return identified;
          }
        };

    CompletableFuture<RefundResult> success =
        CompletableFuture.supplyAsync(
            () -> service(pausedSuccessRepository).succeed(refund.refundId()));
    assertThat(successReadyToCommit.await(10, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<RefundReconciliationResult> reconciliation =
        CompletableFuture.supplyAsync(
            () -> service(observedReconciliationRepository).reconcile(refund.refundId()));
    assertThat(reconciliationReadOldRefund.await(10, TimeUnit.SECONDS)).isTrue();
    releaseSuccessCommit.countDown();

    assertThat(success.get(10, TimeUnit.SECONDS).state()).isEqualTo("SUCCEEDED");
    assertThat(reconciliation.get(10, TimeUnit.SECONDS).outcome())
        .isEqualTo(RefundReconciliationResult.Outcome.CONSISTENT);
    assertThat(attemptRefunded(paid.attemptId())).isEqualTo(900);
    assertThat(refundMovementCount(paid.orderId())).isOne();
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
    String callbackKey = "callback-refund-" + suffix;
    MockPaymentCallbackRequest callback =
        new MockPaymentCallbackRequest(
            UUID.randomUUID().toString(),
            attempt.callbackCorrelationId(),
            orderId,
            amount,
            "AUD",
            "SUCCEEDED");
    payments.callback(callbackKey, callback);
    return new PaidFixture(orderId, attempt.attemptId(), productId, callbackKey, callback);
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

  private ActionReceiptView concurrentConfirm(
      CountDownLatch ready,
      CountDownLatch release,
      ActionRequestContext context,
      String pendingActionId) {
    ready.countDown();
    try {
      if (!release.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Action confirmation race was not released");
      }
      return actions.confirm(context, pendingActionId);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Action confirmation race was interrupted", exception);
    }
  }

  private ResponseEntity<JsonNode> prepareAction(
      String token,
      String session,
      String traceId,
      String turnId,
      String sandboxId,
      Map<String, Object> body) {
    HttpHeaders headers = actionHeaders(token, session, traceId, turnId, sandboxId);
    return http.exchange(
        "/internal/tools/actions/prepare",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        JsonNode.class);
  }

  private ResponseEntity<JsonNode> confirmAction(
      String token,
      String session,
      String traceId,
      String turnId,
      String sandboxId,
      String pendingActionId,
      Map<String, Object> body) {
    HttpHeaders headers = actionHeaders(token, session, traceId, turnId, sandboxId);
    return http.exchange(
        "/internal/tools/actions/" + pendingActionId + "/confirm",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        JsonNode.class);
  }

  private static HttpHeaders actionHeaders(
      String token, String session, String traceId, String turnId, String sandboxId) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    headers.set("X-Support-Session-Id", session);
    headers.set("X-Agent-Trace-Id", traceId);
    headers.set("X-Agent-Turn-Id", turnId);
    if (sandboxId != null) {
      headers.set("X-Eval-Sandbox-Id", sandboxId);
    }
    return headers;
  }

  private static void awaitLatch(CountDownLatch latch, String message) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException(message);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(message, exception);
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

  private long paymentMovementCount(String orderId) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE order_id = ? AND movement_type = 'STANDARD_PAYMENT'",
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

  private long actionReceiptCount(String pendingActionId) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM action_receipt WHERE pending_action_id = ?",
            Long.class,
            pendingActionId);
    return count == null ? 0 : count;
  }

  private String receiptOutboxId(String receiptId) {
    return jdbc.queryForObject(
        "SELECT outbox_event_id FROM action_receipt WHERE receipt_id = ?", String.class, receiptId);
  }

  private JdbcTemplate rootJdbc() {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            required("CATALOG_MYSQL_URL"), "root", required("MYSQL_BOOTSTRAP_PASSWORD")));
  }

  private static void restoreOutbox(JdbcTemplate target, Map<String, Object> row) {
    target.update(
        """
        INSERT INTO commerce_outbox
          (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload,
           publication_state, publish_attempts, created_at, published_at)
        VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?)
        """,
        row.get("event_id"),
        row.get("aggregate_type"),
        row.get("aggregate_id"),
        row.get("aggregate_version"),
        row.get("event_type"),
        row.get("payload").toString(),
        row.get("publication_state"),
        row.get("publish_attempts"),
        row.get("created_at"),
        row.get("published_at"));
  }

  private static Object originalOutboxValue(Map<String, Object> row, String column) {
    Object value = row.get(column);
    if ("payload".equals(column) && value != null) {
      return value.toString();
    }
    return value;
  }

  private List<String> pendingState(String pendingActionId) {
    return jdbc.queryForObject(
        """
        SELECT state, state_version, consumed_at
        FROM pending_action WHERE pending_action_id = ?
        """,
        (row, index) ->
            java.util.Arrays.asList(
                row.getString("state"),
                row.getString("state_version"),
                row.getString("consumed_at")),
        pendingActionId);
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

  private static String signedOboToken(String subject, String session, String scope, String sandbox)
      throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience("commerce-service")
            .subject(subject)
            .claim("user_id", subject)
            .claim("token_type", "agent_obo")
            .claim("session", session)
            .claim("scope", scope)
            .claim("act", Map.of("azp", "agent-service"))
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString());
    if (sandbox != null) {
      claims.claim("sandbox", sandbox);
    }
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("catalog-current").build(),
            claims.build());
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

  private record PaidFixture(
      String orderId,
      String attemptId,
      String productId,
      String callbackKey,
      MockPaymentCallbackRequest callback) {}

  private record RawRefund(String refundId, String attemptId) {}

  private record ColumnCorruption(String column, Object value) {}

  private record SeckillFixture(
      String orderId, String productId, String activityId, SeckillTimeoutMessage timeout) {}
}
