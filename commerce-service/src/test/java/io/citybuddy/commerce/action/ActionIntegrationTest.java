package io.citybuddy.commerce.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.citybuddy.commerce.mysql.BoundedMySqlTransactions;
import io.citybuddy.commerce.order.StandardOrderIntentCommitment;
import io.citybuddy.commerce.payment.MockPaymentCallbackRequest;
import io.citybuddy.commerce.payment.MockPaymentRequest;
import io.citybuddy.commerce.payment.MockPaymentResult;
import io.citybuddy.commerce.payment.MockPaymentService;
import io.citybuddy.commerce.refund.RefundService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActionIntegrationTest {
  private static final String USER = "catalog-user";
  private static final String SESSION = "action-session";
  private static final String SCOPE = "refund:create";

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
    registry.add("citybuddy.refund.required-permission", () -> SCOPE);
    registry.add("citybuddy.refund.lock-wait-timeout-seconds", () -> "1");
    registry.add("citybuddy.refund.maximum-observation-attempts", () -> "2");
    registry.add("citybuddy.refund.observation-backoff", () -> "25ms");
    registry.add("citybuddy.obo.enabled", () -> "true");
    registry.add("citybuddy.obo.issuer", () -> "https://identity.citybuddy.test");
    registry.add("citybuddy.obo.jwks-url", () -> required("IDENTITY_JWKS_URL"));
    registry.add("citybuddy.obo.clock-skew", () -> "30s");
    registry.add("citybuddy.obo.jwks-cache-ttl", () -> "30s");
    registry.add("citybuddy.actions.enabled", () -> "true");
    registry.add("citybuddy.actions.required-scope", () -> SCOPE);
    registry.add("citybuddy.actions.pending-ttl", () -> "15m");
    registry.add("citybuddy.actions.lock-wait-timeout-seconds", () -> "1");
    registry.add("citybuddy.actions.maximum-observation-attempts", () -> "2");
    registry.add("citybuddy.actions.observation-backoff", () -> "25ms");
  }

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MockPaymentService payments;
  @Autowired private RefundService refunds;
  @Autowired private ActionService actions;
  @Autowired private PlatformTransactionManager transactionManager;
  private SingleConnectionDataSource corruptionDataSource;
  private JdbcTemplate corruptionJdbc;

  @Autowired
  private ObjectProvider<io.citybuddy.commerce.evaluation.EvaluationSandboxAccess> sandboxAccess;

  @AfterEach
  void closeCorruptionConnection() {
    if (corruptionDataSource != null) {
      corruptionDataSource.destroy();
      corruptionDataSource = null;
      corruptionJdbc = null;
    }
  }

  @Test
  void oboPrepareConfirmConcurrentReplayAndClosureAreAtomic() throws Exception {
    PaidFixture paid = seedPaidStandard(900, "action-main");
    String turn = UUID.randomUUID().toString();

    ResponseEntity<JsonNode> prepared =
        prepare(obo(USER, SESSION, SCOPE), SESSION, "trace-main", turn, paid.orderId(), 400);
    assertThat(prepared.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String pendingId = prepared.getBody().get("pendingActionId").asText();
    assertThat(prepared.getBody().get("state").asText()).isEqualTo("PREPARED");
    assertThat(prepared.getBody().get("replayed").asBoolean()).isFalse();

    ResponseEntity<JsonNode> prepareReplay =
        prepare(obo(USER, SESSION, SCOPE), SESSION, "trace-main", turn, paid.orderId(), 400);
    assertThat(prepareReplay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(prepareReplay.getBody().get("pendingActionId").asText()).isEqualTo(pendingId);
    assertThat(
            prepare(obo(USER, SESSION, SCOPE), SESSION, "trace-main", turn, paid.orderId(), 401)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            prepare(
                    obo(USER, "other-session", SCOPE),
                    SESSION,
                    "trace-main",
                    turn,
                    paid.orderId(),
                    400)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);

    ActionRequestContext context =
        new ActionRequestContext(USER, SESSION, "trace-main", turn, null, SCOPE);
    CompletableFuture<ActionReceiptView> first =
        CompletableFuture.supplyAsync(() -> actions.confirm(context, pendingId));
    CompletableFuture<ActionReceiptView> second =
        CompletableFuture.supplyAsync(() -> actions.confirm(context, pendingId));
    ActionReceiptView left = first.get(10, TimeUnit.SECONDS);
    ActionReceiptView right = second.get(10, TimeUnit.SECONDS);
    assertThat(left.receiptId()).isEqualTo(right.receiptId());
    assertThat(left.refundId()).isEqualTo(right.refundId());

    ResponseEntity<JsonNode> replay =
        confirm(obo(USER, SESSION, SCOPE), SESSION, "trace-main", turn, pendingId);
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getBody().get("receiptId").asText()).isEqualTo(left.receiptId());
    assertThat(replay.getBody().get("replayed").asBoolean()).isTrue();
    assertThat(rowCount("pending_action", "pending_action_id", pendingId)).isOne();
    assertThat(rowCount("action_receipt", "pending_action_id", pendingId)).isOne();
    assertThat(rowCount("mock_refund", "order_id", paid.orderId())).isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type = 'REFUND' "
                    + "AND aggregate_id = ? AND event_type = 'REFUND_REQUESTED'",
                Long.class,
                left.refundId()))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT CONCAT(state, ':', state_version, ':', consumed_at IS NOT NULL) "
                    + "FROM pending_action WHERE pending_action_id = ?",
                String.class,
                pendingId))
        .isEqualTo("CONSUMED:2:1");
  }

  @Test
  void failureAfterRefundAndAfterConsumeRollsBackTheEntireActionUnit() {
    PaidFixture paid = seedPaidStandard(700, "action-rollback");
    String turn = UUID.randomUUID().toString();
    ActionRequestContext context =
        new ActionRequestContext(USER, SESSION, "trace-rollback", turn, null, SCOPE);
    PendingActionView pending =
        actions.prepare(
            context, new PrepareActionCommand("REFUND_REQUEST", paid.orderId(), 300L, "AUD"));

    ActionRepository failAfterRefund =
        new ActionRepository(jdbc, objectMapper) {
          @Override
          public void consume(PendingActionRecord action, Instant committedAt) {
            throw new IllegalStateException("controlled failure after refund and Outbox");
          }
        };
    ActionService failingBeforeConsume = actionService(failAfterRefund);
    assertThatThrownBy(() -> failingBeforeConsume.confirm(context, pending.pendingActionId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled failure after refund and Outbox");
    assertPreparedWithoutEffects(pending.pendingActionId(), paid.orderId());

    ActionRepository failAfterConsume =
        new ActionRepository(jdbc, objectMapper) {
          @Override
          public void insertReceipt(ActionReceiptRecord receipt) {
            throw new IllegalStateException("controlled failure after consume");
          }
        };
    ActionService failing = actionService(failAfterConsume);
    assertThatThrownBy(() -> failing.confirm(context, pending.pendingActionId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled failure after consume");
    assertPreparedWithoutEffects(pending.pendingActionId(), paid.orderId());

    ActionReceiptView committed = actions.confirm(context, pending.pendingActionId());
    assertThat(committed.status()).isEqualTo("REQUESTED");
    assertThat(rowCount("mock_refund", "order_id", paid.orderId())).isOne();
    assertThat(rowCount("action_receipt", "pending_action_id", pending.pendingActionId())).isOne();
  }

  @Test
  void expiredOrDamagedTruthRejectsWithoutSecondMutation() throws Exception {
    PaidFixture expiredPaid = seedPaidStandard(600, "action-expired");
    String expiredTurn = UUID.randomUUID().toString();
    ActionRequestContext expiredContext =
        new ActionRequestContext(USER, SESSION, "trace-expired", expiredTurn, null, SCOPE);
    PendingActionView expired =
        actions.prepare(
            expiredContext,
            new PrepareActionCommand("REFUND_REQUEST", expiredPaid.orderId(), 200L, "AUD"));
    ActionService afterExpiry =
        actionService(
            new ActionRepository(jdbc, objectMapper),
            Clock.offset(Clock.systemUTC(), Duration.ofMinutes(16)));
    assertThatThrownBy(() -> afterExpiry.confirm(expiredContext, expired.pendingActionId()))
        .isInstanceOfSatisfying(
            ActionException.class, exception -> assertThat(exception.status()).isEqualTo(409));
    assertPreparedWithoutEffects(expired.pendingActionId(), expiredPaid.orderId());

    PaidFixture damagedPaid = seedPaidStandard(500, "action-damaged");
    String damagedTurn = UUID.randomUUID().toString();
    ActionRequestContext damagedContext =
        new ActionRequestContext(USER, SESSION, "trace-damaged", damagedTurn, null, SCOPE);
    PendingActionView damaged =
        actions.prepare(
            damagedContext,
            new PrepareActionCommand("REFUND_REQUEST", damagedPaid.orderId(), 200L, "AUD"));
    ActionReceiptView receipt = actions.confirm(damagedContext, damaged.pendingActionId());
    corruptionJdbc()
        .update(
            "DELETE FROM commerce_outbox WHERE event_id = ?", receiptOutbox(receipt.receiptId()));
    assertThatThrownBy(() -> actions.confirm(damagedContext, damaged.pendingActionId()))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_DURABLE_TRUTH_INCONSISTENT);
            });
    assertThat(rowCount("mock_refund", "order_id", damagedPaid.orderId())).isOne();
    assertThat(rowCount("action_receipt", "pending_action_id", damaged.pendingActionId())).isOne();
  }

  @Test
  void alternativeUniqueLocatorsCannotHideContradictoryActionTruth() {
    PaidFixture preparePaid = seedPaidStandard(700, "action-alternative-pending-key");
    String orphanTurn = UUID.randomUUID().toString();
    ActionRequestContext orphanContext =
        new ActionRequestContext(USER, SESSION, "trace-orphan-pending", orphanTurn, null, SCOPE);
    PendingActionView orphan =
        actions.prepare(
            orphanContext,
            new PrepareActionCommand("REFUND_REQUEST", preparePaid.orderId(), 200L, "AUD"));
    String requestedTurn = UUID.randomUUID().toString();
    ActionRequestContext requestedContext =
        new ActionRequestContext(
            USER, SESSION, "trace-requested-pending", requestedTurn, null, SCOPE);
    String requestedArgumentHash =
        ActionCanonical.hash("REFUND_REQUEST", preparePaid.orderId(), "200", "AUD");
    String requestedActionKey =
        ActionCanonical.hash(USER, SESSION, requestedTurn, "REFUND_REQUEST", requestedArgumentHash);
    assertThat(
            corruptionJdbc()
                .update(
                    "UPDATE pending_action SET action_idempotency_key = ? "
                        + "WHERE pending_action_id = ?",
                    requestedActionKey,
                    orphan.pendingActionId()))
        .isOne();

    assertThatThrownBy(
            () ->
                actions.prepare(
                    requestedContext,
                    new PrepareActionCommand("REFUND_REQUEST", preparePaid.orderId(), 200L, "AUD")))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_DURABLE_TRUTH_INCONSISTENT);
            });
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM pending_action WHERE user_subject = ? "
                    + "AND support_session_id = ? AND turn_id = ?",
                Long.class,
                USER,
                SESSION,
                requestedTurn))
        .isZero();

    PaidFixture receiptPaid = seedPaidStandard(800, "action-alternative-receipt-key");
    String receiptTurn = UUID.randomUUID().toString();
    ActionRequestContext receiptContext =
        new ActionRequestContext(
            USER, SESSION, "trace-alternative-receipt", receiptTurn, null, SCOPE);
    PendingActionView pending =
        actions.prepare(
            receiptContext,
            new PrepareActionCommand("REFUND_REQUEST", receiptPaid.orderId(), 300L, "AUD"));
    String actionKey =
        jdbc.queryForObject(
            "SELECT action_idempotency_key FROM pending_action WHERE pending_action_id = ?",
            String.class,
            pending.pendingActionId());
    String receiptKey = ActionCanonical.hash("ACTION_RECEIPT", actionKey);
    Instant orphanCommittedAt = Instant.now().minusSeconds(1);
    assertThat(
            corruptionJdbc()
                .update(
                    """
                    INSERT INTO action_receipt
                      (receipt_id, receipt_idempotency_key, pending_action_id, action_type,
                       argument_hash, result_hash, user_subject, support_session_id, trace_id,
                       turn_id, sandbox_id, order_id, payment_attempt_id, refund_id,
                       resulting_resource_version, result_state, amount_minor, currency,
                       outbox_event_id, outbox_created_at, committed_at)
                    VALUES (?, ?, ?, 'REFUND_REQUEST', ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, 1,
                            'REQUESTED', 300, 'AUD', ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    receiptKey,
                    UUID.randomUUID().toString(),
                    "a".repeat(64),
                    "b".repeat(64),
                    USER,
                    SESSION,
                    "trace-orphan-receipt",
                    UUID.randomUUID().toString(),
                    receiptPaid.orderId(),
                    receiptPaid.attemptId(),
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    Timestamp.from(orphanCommittedAt),
                    Timestamp.from(orphanCommittedAt)))
        .isOne();

    assertThatThrownBy(() -> actions.confirm(receiptContext, pending.pendingActionId()))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_DURABLE_TRUTH_INCONSISTENT);
            });
    assertPreparedWithoutEffects(pending.pendingActionId(), receiptPaid.orderId());
  }

  @Test
  void receiptReplayAcceptsLegalRefundProgressAndRejectsMissingDuplicateOrContradictoryClosure() {
    ConfirmedAction progressed = confirmAction("action-progressed", 800, 300);
    refunds.markProcessing(progressed.receipt().refundId());
    refunds.succeed(progressed.receipt().refundId());
    ActionReceiptView progressedReplay =
        actions.confirm(progressed.context(), progressed.pending().pendingActionId());
    assertThat(progressedReplay.receiptId()).isEqualTo(progressed.receipt().receiptId());
    assertThat(progressedReplay.refundId()).isEqualTo(progressed.receipt().refundId());
    assertThat(progressedReplay.status()).isEqualTo("REQUESTED");
    assertThat(progressedReplay.resourceVersion()).isOne();
    assertThat(progressedReplay.replayed()).isTrue();

    ConfirmedAction duplicateOutbox = confirmAction("action-outbox-sibling", 700, 200);
    corruptionJdbc()
        .update(
            """
            INSERT INTO commerce_outbox
              (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload)
            VALUES (?, 'CORRUPTED', ?, 99, 'CORRUPTED', JSON_OBJECT())
            """,
            UUID.randomUUID().toString(),
            duplicateOutbox.receipt().refundId());
    assertReplayIntegrityFailure(duplicateOutbox);

    ConfirmedAction boundedOutbox = confirmAction("action-outbox-bound", 700, 200);
    for (int version = 4; version <= 131; version++) {
      corruptionJdbc()
          .update(
              """
              INSERT INTO commerce_outbox
                (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload)
              VALUES (?, 'CORRUPTED', ?, ?, 'CORRUPTED', JSON_OBJECT())
              """,
              UUID.randomUUID().toString(),
              boundedOutbox.receipt().refundId(),
              version);
    }
    assertReplayIntegrityFailure(boundedOutbox);

    ConfirmedAction missingCallback = confirmAction("action-callback-missing", 600, 200);
    assertThat(
            corruptionJdbc()
                .update(
                    "DELETE FROM mock_payment_callback WHERE attempt_id = ?",
                    missingCallback.paid().attemptId()))
        .isOne();
    assertReplayIntegrityFailure(missingCallback);

    ConfirmedAction missingRefund = confirmAction("action-refund-missing", 500, 200);
    assertThat(
            corruptionJdbc()
                .update(
                    "DELETE FROM mock_refund WHERE refund_id = ?",
                    missingRefund.receipt().refundId()))
        .isOne();
    assertReplayIntegrityFailure(missingRefund);

    ConfirmedAction missingLedger = confirmAction("action-ledger-missing", 900, 300);
    refunds.markProcessing(missingLedger.receipt().refundId());
    refunds.succeed(missingLedger.receipt().refundId());
    assertThat(
            corruptionJdbc()
                .update(
                    "DELETE FROM inventory_ledger WHERE business_event_key = ?",
                    "mock-refund:" + missingLedger.receipt().refundId()))
        .isOne();
    assertReplayIntegrityFailure(missingLedger);
  }

  @Test
  void immutablePendingAndReceiptColumnsAreCommittedAndVisibilityRemainsConcealed() {
    PaidFixture paid = seedPaidStandard(800, "action-column-commitment");
    String turn = UUID.randomUUID().toString();
    ActionRequestContext context =
        new ActionRequestContext(USER, SESSION, "trace-columns", turn, null, SCOPE);
    PendingActionView pending =
        actions.prepare(
            context, new PrepareActionCommand("REFUND_REQUEST", paid.orderId(), 300L, "AUD"));

    Map<String, Object> pendingRow =
        corruptionJdbc()
            .queryForMap(
                "SELECT * FROM pending_action WHERE pending_action_id = ?",
                pending.pendingActionId());
    List<ColumnFault> pendingFaults =
        List.of(
            new ColumnFault("action_idempotency_key", "a".repeat(64)),
            new ColumnFault("pending_hash", "b".repeat(64)),
            new ColumnFault("argument_hash", "c".repeat(64)),
            new ColumnFault("trace_id", "tampered-trace"),
            new ColumnFault("turn_id", UUID.randomUUID().toString()),
            new ColumnFault("required_scope", "other:scope"),
            new ColumnFault("order_id", UUID.randomUUID().toString()),
            new ColumnFault("order_kind", "SECKILL"),
            new ColumnFault("payment_attempt_id", UUID.randomUUID().toString()),
            new ColumnFault(
                "target_order_version",
                ((Number) pendingRow.get("target_order_version")).longValue() + 1),
            new ColumnFault(
                "amount_minor", ((Number) pendingRow.get("amount_minor")).longValue() + 1),
            new ColumnFault("currency", "USD"),
            new ColumnFault(
                "expires_at",
                Timestamp.from(
                    ((Timestamp) pendingRow.get("expires_at")).toInstant().plusSeconds(1))),
            new ColumnFault(
                "created_at",
                Timestamp.from(
                    ((Timestamp) pendingRow.get("created_at")).toInstant().minusSeconds(1))));
    for (ColumnFault fault : pendingFaults) {
      assertColumnDamage(
          "pending_action",
          "pending_action_id",
          pending.pendingActionId(),
          fault,
          () -> actions.confirm(context, pending.pendingActionId()));
      assertPreparedWithoutEffects(pending.pendingActionId(), paid.orderId());
    }
    for (ColumnFault fault :
        List.of(
            new ColumnFault("user_subject", "other-owner"),
            new ColumnFault("support_session_id", "other-session"),
            new ColumnFault("sandbox_id", "other-sandbox"))) {
      assertColumnRejection(
          "pending_action",
          "pending_action_id",
          pending.pendingActionId(),
          fault,
          ActionRejectionReason.ACTION_CONCEALED_NOT_FOUND,
          404,
          () -> actions.confirm(context, pending.pendingActionId()));
      assertPreparedWithoutEffects(pending.pendingActionId(), paid.orderId());
    }

    ActionReceiptView receipt = actions.confirm(context, pending.pendingActionId());
    Map<String, Object> receiptRow =
        corruptionJdbc()
            .queryForMap("SELECT * FROM action_receipt WHERE receipt_id = ?", receipt.receiptId());
    List<ColumnFault> receiptFaults =
        List.of(
            new ColumnFault("receipt_id", UUID.randomUUID().toString()),
            new ColumnFault("receipt_idempotency_key", "d".repeat(64)),
            new ColumnFault("pending_action_id", UUID.randomUUID().toString()),
            new ColumnFault("argument_hash", "e".repeat(64)),
            new ColumnFault("result_hash", "f".repeat(64)),
            new ColumnFault("user_subject", "other-owner"),
            new ColumnFault("support_session_id", "other-session"),
            new ColumnFault("trace_id", "other-trace"),
            new ColumnFault("turn_id", UUID.randomUUID().toString()),
            new ColumnFault("sandbox_id", "other-sandbox"),
            new ColumnFault("order_id", UUID.randomUUID().toString()),
            new ColumnFault("payment_attempt_id", UUID.randomUUID().toString()),
            new ColumnFault("refund_id", UUID.randomUUID().toString()),
            new ColumnFault(
                "amount_minor", ((Number) receiptRow.get("amount_minor")).longValue() + 1),
            new ColumnFault("currency", "USD"),
            new ColumnFault("outbox_event_id", UUID.randomUUID().toString()),
            new ColumnFault(
                "outbox_created_at",
                Timestamp.from(
                    ((Timestamp) receiptRow.get("outbox_created_at")).toInstant().minusSeconds(1))),
            new ColumnFault(
                "committed_at",
                Timestamp.from(
                    ((Timestamp) receiptRow.get("committed_at")).toInstant().plusSeconds(1))));
    for (ColumnFault fault : receiptFaults) {
      assertColumnDamage(
          "action_receipt",
          "receipt_id",
          receipt.receiptId(),
          fault,
          () -> actions.confirm(context, pending.pendingActionId()));
    }
    assertThat(rowCount("mock_refund", "order_id", paid.orderId())).isOne();
    assertThat(rowCount("action_receipt", "pending_action_id", pending.pendingActionId())).isOne();
  }

  private ActionService actionService(ActionRepository repository) {
    return actionService(repository, Clock.systemUTC());
  }

  private ConfirmedAction confirmAction(String suffix, long paidAmount, long refundAmount) {
    PaidFixture paid = seedPaidStandard(paidAmount, suffix);
    ActionRequestContext context =
        new ActionRequestContext(
            USER, SESSION, "trace-" + suffix, UUID.randomUUID().toString(), null, SCOPE);
    PendingActionView pending =
        actions.prepare(
            context,
            new PrepareActionCommand("REFUND_REQUEST", paid.orderId(), refundAmount, "AUD"));
    return new ConfirmedAction(
        paid, context, pending, actions.confirm(context, pending.pendingActionId()));
  }

  private void assertReplayIntegrityFailure(ConfirmedAction action) {
    long receiptCount =
        rowCount("action_receipt", "pending_action_id", action.pending().pendingActionId());
    long refundOutboxCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_id = ?",
            Long.class,
            action.receipt().refundId());
    assertThatThrownBy(() -> actions.confirm(action.context(), action.pending().pendingActionId()))
        .isInstanceOfSatisfying(
            ActionException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(ActionRejectionReason.ACTION_DURABLE_TRUTH_INCONSISTENT);
            });
    assertThat(rowCount("action_receipt", "pending_action_id", action.pending().pendingActionId()))
        .isEqualTo(receiptCount);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_id = ?",
                Long.class,
                action.receipt().refundId()))
        .isEqualTo(refundOutboxCount);
  }

  private ActionService actionService(ActionRepository repository, Clock clock) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ActionProperties properties =
        new ActionProperties(SCOPE, Duration.ofMinutes(15), 1, 2, Duration.ofMillis(25));
    return new ActionService(
        repository,
        refunds,
        new ActionTransactions(
            new BoundedMySqlTransactions(jdbc, transaction, 1), 2, Duration.ofMillis(25)),
        properties,
        clock,
        sandboxAccess);
  }

  private void assertPreparedWithoutEffects(String pendingId, String orderId) {
    assertThat(
            jdbc.queryForObject(
                "SELECT CONCAT(state, ':', state_version, ':', consumed_at IS NULL) "
                    + "FROM pending_action WHERE pending_action_id = ?",
                String.class,
                pendingId))
        .isEqualTo("PREPARED:1:1");
    assertThat(rowCount("mock_refund", "order_id", orderId)).isZero();
    assertThat(rowCount("action_receipt", "pending_action_id", pendingId)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type = 'REFUND' "
                    + "AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.orderId')) = ?",
                Long.class,
                orderId))
        .isZero();
  }

  private ResponseEntity<JsonNode> prepare(
      String token, String session, String trace, String turn, String orderId, long amount)
      throws Exception {
    HttpHeaders headers = actionHeaders(token, session, trace, turn);
    return http.exchange(
        "/internal/tools/actions/prepare",
        HttpMethod.POST,
        new HttpEntity<>(
            Map.of(
                "actionType",
                "REFUND_REQUEST",
                "arguments",
                Map.of("orderId", orderId, "amountMinor", amount, "currency", "AUD")),
            headers),
        JsonNode.class);
  }

  private ResponseEntity<JsonNode> confirm(
      String token, String session, String trace, String turn, String pendingId) {
    return http.exchange(
        "/internal/tools/actions/" + pendingId + "/confirm",
        HttpMethod.POST,
        new HttpEntity<>(null, actionHeaders(token, session, trace, turn)),
        JsonNode.class);
  }

  private static HttpHeaders actionHeaders(
      String token, String session, String trace, String turn) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.set("X-Support-Session-Id", session);
    headers.set("X-Agent-Trace-Id", trace);
    headers.set("X-Agent-Turn-Id", turn);
    return headers;
  }

  private PaidFixture seedPaidStandard(long amount, String suffix) {
    String orderId = UUID.randomUUID().toString();
    String productId = "action-product-" + suffix;
    jdbc.update(
        """
        INSERT INTO standard_order
          (order_id, user_subject, product_id, product_name, unit_price_minor, currency,
           quantity, total_price_minor, product_version)
        VALUES (?, ?, ?, 'Action fixture', ?, 'AUD', 1, ?, 1)
        """,
        orderId,
        USER,
        productId,
        amount,
        amount);
    jdbc.update(
        """
        INSERT INTO order_idempotency (user_subject, idempotency_key, intent_hash, order_id)
        VALUES (?, ?, ?, ?)
        """,
        USER,
        "action-origin-" + orderId,
        StandardOrderIntentCommitment.hash(productId, 1, 1),
        orderId);
    MockPaymentResult attempt =
        payments.start(
            USER, orderId, "action-payment-" + suffix, new MockPaymentRequest(amount, "AUD", null));
    payments.callback(
        "action-callback-" + suffix,
        new MockPaymentCallbackRequest(
            UUID.randomUUID().toString(),
            attempt.callbackCorrelationId(),
            orderId,
            amount,
            "AUD",
            "SUCCEEDED"));
    return new PaidFixture(orderId, attempt.attemptId());
  }

  private long rowCount(String table, String key, String value) {
    if (!List.of("pending_action", "action_receipt", "mock_refund").contains(table)
        || !List.of("pending_action_id", "order_id").contains(key)) {
      throw new IllegalArgumentException("Unregistered test count target");
    }
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE " + key + " = ?", Long.class, value);
  }

  private String receiptOutbox(String receiptId) {
    return jdbc.queryForObject(
        "SELECT outbox_event_id FROM action_receipt WHERE receipt_id = ?", String.class, receiptId);
  }

  private void assertColumnDamage(
      String table, String key, String locator, ColumnFault fault, Runnable replay) {
    assertColumnRejection(
        table,
        key,
        locator,
        fault,
        ActionRejectionReason.ACTION_DURABLE_TRUTH_INCONSISTENT,
        409,
        replay);
  }

  private void assertColumnRejection(
      String table,
      String key,
      String locator,
      ColumnFault fault,
      ActionRejectionReason reason,
      int status,
      Runnable replay) {
    if (!List.of("pending_action", "action_receipt").contains(table)
        || !List.of("pending_action_id", "receipt_id").contains(key)
        || !List.of(
                "action_idempotency_key",
                "pending_hash",
                "argument_hash",
                "user_subject",
                "support_session_id",
                "trace_id",
                "turn_id",
                "required_scope",
                "sandbox_id",
                "order_id",
                "order_kind",
                "payment_attempt_id",
                "target_order_version",
                "amount_minor",
                "currency",
                "expires_at",
                "created_at",
                "receipt_id",
                "receipt_idempotency_key",
                "pending_action_id",
                "result_hash",
                "refund_id",
                "outbox_event_id",
                "outbox_created_at",
                "committed_at")
            .contains(fault.column())) {
      throw new IllegalArgumentException("Unregistered Action integrity injection target");
    }
    Map<String, Object> current =
        corruptionJdbc()
            .queryForMap(
                "SELECT " + fault.column() + " FROM " + table + " WHERE " + key + " = ?", locator);
    Object original = current.get(fault.column());
    try {
      assertThat(
              corruptionJdbc()
                  .update(
                      "UPDATE " + table + " SET " + fault.column() + " = ? WHERE " + key + " = ?",
                      fault.value(),
                      locator))
          .isOne();
      assertThatThrownBy(replay::run)
          .isInstanceOfSatisfying(
              ActionException.class,
              exception -> {
                assertThat(exception.status()).isEqualTo(status);
                assertThat(exception.reason()).isEqualTo(reason);
              });
    } finally {
      String restoreKey = key;
      String restoreLocator = locator;
      if (fault.column().equals(key)) {
        restoreLocator = fault.value().toString();
      }
      assertThat(
              corruptionJdbc()
                  .update(
                      "UPDATE "
                          + table
                          + " SET "
                          + fault.column()
                          + " = ? WHERE "
                          + restoreKey
                          + " = ?",
                      original,
                      restoreLocator))
          .isOne();
    }
  }

  private JdbcTemplate corruptionJdbc() {
    if (corruptionJdbc == null) {
      corruptionDataSource =
          new SingleConnectionDataSource(
              required("CATALOG_MYSQL_URL"),
              "bootstrap_admin",
              required("MYSQL_BOOTSTRAP_PASSWORD"),
              true);
      corruptionJdbc = new JdbcTemplate(corruptionDataSource);
      corruptionJdbc.execute("SET ROLE bootstrap_grant_role");
    }
    return corruptionJdbc;
  }

  private static String obo(String subject, String session, String scope) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience("commerce-service")
            .subject(subject)
            .claim("user_id", subject)
            .claim("session", session)
            .claim("scope", scope)
            .claim("token_type", "agent_obo")
            .claim("act", Map.of("azp", "agent-service"))
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

  private record PaidFixture(String orderId, String attemptId) {}

  private record ConfirmedAction(
      PaidFixture paid,
      ActionRequestContext context,
      PendingActionView pending,
      ActionReceiptView receipt) {}

  private record ColumnFault(String column, Object value) {}
}
