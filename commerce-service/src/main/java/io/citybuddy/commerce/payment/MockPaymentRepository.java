package io.citybuddy.commerce.payment;

import io.citybuddy.commerce.evaluation.EvaluationAuditEntityType;
import io.citybuddy.commerce.evaluation.EvaluationAuditReferenceIdentity;
import io.citybuddy.commerce.evaluation.EvaluationAuditReferenceWriter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class MockPaymentRepository {
  private final JdbcTemplate jdbc;

  public MockPaymentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<OrderTruth> findOrderForUpdate(String orderId) {
    return findOrder(orderId, " FOR UPDATE");
  }

  private Optional<OrderTruth> findOrder(String orderId, String lockClause) {
    List<OrderTruth> rows = enumerateOrderClosure(orderId, lockClause);
    if (rows.size() > 1) {
      throw new MockPaymentIntegrityException("Payment order identifier is ambiguous");
    }
    return rows.stream().findFirst();
  }

  List<OrderTruth> enumerateOrderClosure(String orderId, String lockClause) {
    requireEnumerationKeys(EvaluationPaymentCommittedFaces.ORDER.enumerationKeys(), "order_id");
    List<OrderTruth> rows = new java.util.ArrayList<>();
    rows.addAll(
        jdbc.query(
            EvaluationPaymentCommittedFaces.standardOrderByIdSql(lockClause),
            (result, row) -> mapOrder(result, "STANDARD"),
            orderId));
    rows.addAll(
        jdbc.query(
            EvaluationPaymentCommittedFaces.seckillOrderByIdSql(lockClause),
            (result, row) -> mapOrder(result, "SECKILL"),
            orderId));
    return List.copyOf(rows);
  }

  public Optional<OrderTruth> findEvaluationOrderForUpdate(String orderId, String sandboxId) {
    return findEvaluationOrder(orderId, sandboxId, " FOR UPDATE");
  }

  private Optional<OrderTruth> findEvaluationOrder(
      String orderId, String sandboxId, String lockClause) {
    return findOrder(orderId, lockClause).filter(row -> sandboxId.equals(row.sandboxId()));
  }

  public Optional<AttemptRecord> findAttemptByRequestForUpdate(String user, String key) {
    return queryAttempt(
        "SELECT "
            + attemptColumns()
            + " FROM "
            + attemptTable()
            + " WHERE user_subject = ? "
            + "AND request_idempotency_key = ? FOR UPDATE",
        user,
        key);
  }

  public void bindEvaluationOrderOwner(
      String orderId, String sandboxId, String ownerHandle, String userSubject) {
    int changed =
        jdbc.update(
            """
            UPDATE standard_order
            SET user_subject = ?
            WHERE order_id = ? AND sandbox_id = ? AND evaluation_owner_handle = ?
              AND user_subject = ? AND status = 'UNPAID' AND state_version = 1
            """,
            userSubject,
            orderId,
            sandboxId,
            ownerHandle,
            io.citybuddy.commerce.evaluation.EvaluationSandboxRepository.fixtureOwner(ownerHandle));
    if (changed != 1) {
      throw new IllegalStateException("Evaluation payment owner binding did not persist");
    }
  }

  public Optional<AttemptRecord> findAttemptByOrderForUpdate(String orderKind, String orderId) {
    return queryAttempt(
        "SELECT "
            + attemptColumns()
            + " FROM "
            + attemptTable()
            + " WHERE order_kind = ? AND order_id = ? FOR UPDATE",
        orderKind,
        orderId);
  }

  public Optional<AttemptRecord> findAttemptByCorrelationForUpdate(String correlationId) {
    return queryAttempt(
        "SELECT "
            + attemptColumns()
            + " FROM "
            + attemptTable()
            + " WHERE callback_correlation_id = ? FOR UPDATE",
        correlationId);
  }

  public Optional<AttemptRecord> findAttemptByCorrelation(String correlationId) {
    return queryAttempt(
        "SELECT "
            + attemptColumns()
            + " FROM "
            + attemptTable()
            + " WHERE callback_correlation_id = ?",
        correlationId);
  }

  public Optional<AttemptRecord> findEvaluationAttemptByCorrelationForUpdate(
      String correlationId, String sandboxId) {
    return queryAttempt(
            "SELECT "
                + attemptColumns()
                + " FROM "
                + attemptTable()
                + " WHERE callback_correlation_id = ? FOR UPDATE",
            correlationId)
        .filter(attempt -> sandboxId.equals(attempt.sandboxId()));
  }

  public Optional<AttemptRecord> findAttemptByIdForUpdate(String attemptId) {
    return queryAttempt(
        "SELECT "
            + attemptColumns()
            + " FROM "
            + attemptTable()
            + " WHERE attempt_id = ? FOR UPDATE",
        attemptId);
  }

  public Optional<AttemptRecord> findAttemptById(String attemptId) {
    return queryAttempt(
        "SELECT " + attemptColumns() + " FROM " + attemptTable() + " WHERE attempt_id = ?",
        attemptId);
  }

  public void insertAttempt(AttemptRecord attempt) {
    jdbc.update(
        """
        INSERT INTO mock_payment_attempt
          (attempt_id, callback_correlation_id, user_subject, order_id, order_kind,
           sandbox_id, request_idempotency_key, intent_hash, amount_minor, currency)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        attempt.attemptId(),
        attempt.callbackCorrelationId(),
        attempt.userSubject(),
        attempt.orderId(),
        attempt.orderKind(),
        attempt.sandboxId(),
        attempt.requestIdempotencyKey(),
        attempt.intentHash(),
        attempt.amountMinor(),
        attempt.currency());
  }

  public Optional<CallbackRecord> findCallbackByKey(String idempotencyKey) {
    return queryCallback(
        "SELECT "
            + callbackColumns()
            + " FROM "
            + callbackTable()
            + " WHERE callback_idempotency_key = ?",
        idempotencyKey);
  }

  public Optional<CallbackRecord> findCallbackByEvent(String eventId) {
    return queryCallback(
        "SELECT " + callbackColumns() + " FROM " + callbackTable() + " WHERE callback_event_id = ?",
        eventId);
  }

  public Optional<CallbackRecord> findCallbackByCorrelation(String correlationId) {
    return queryCallback(
        "SELECT "
            + callbackColumns()
            + " FROM "
            + callbackTable()
            + " WHERE callback_correlation_id = ?",
        correlationId);
  }

  public Optional<CallbackRecord> findCallbackByAttempt(String attemptId) {
    return queryCallback(
        "SELECT " + callbackColumns() + " FROM " + callbackTable() + " WHERE attempt_id = ?",
        attemptId);
  }

  public void markOrderPaid(OrderTruth order) {
    String table = "STANDARD".equals(order.orderKind()) ? "standard_order" : "seckill_order";
    int changed =
        jdbc.update(
            "UPDATE "
                + table
                + " SET status = 'PAID', state_version = 2 "
                + "WHERE order_id = ? AND status = 'UNPAID' AND state_version = 1",
            order.orderId());
    if (changed != 1) {
      throw new IllegalStateException("Payment order changed during its locked transition");
    }
  }

  public void markAttemptSucceeded(AttemptRecord attempt, Instant succeededAt) {
    int changed =
        jdbc.update(
            """
            UPDATE mock_payment_attempt
            SET state = 'SUCCEEDED', state_version = 2, succeeded_at = ?
            WHERE attempt_id = ? AND state = 'PENDING' AND state_version = 1
            """,
            Timestamp.from(succeededAt),
            attempt.attemptId());
    if (changed != 1) {
      throw new IllegalStateException("Payment attempt changed during its locked transition");
    }
  }

  public void insertPaymentMovement(AttemptRecord attempt, OrderTruth order) {
    String movementType = order.orderKind() + "_PAYMENT";
    jdbc.update(
        """
        INSERT INTO inventory_ledger
          (movement_id, business_event_key, movement_type, order_id, reservation_id,
           activity_id, product_id, sandbox_id, inventory_delta, activity_quota_delta,
           payment_amount_minor, payment_currency)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)
        """,
        UUID.randomUUID().toString(),
        "mock-payment:" + attempt.attemptId(),
        movementType,
        order.orderId(),
        order.reservationId(),
        order.activityId(),
        order.productId(),
        attempt.sandboxId(),
        attempt.amountMinor(),
        attempt.currency());
  }

  public void insertCallback(CallbackRecord callback, Instant createdAt) {
    jdbc.update(
        """
        INSERT INTO mock_payment_callback
          (callback_event_id, callback_idempotency_key, attempt_id,
           callback_correlation_id, sandbox_id, support_session_id, trace_id, operation_id,
           intent_hash, requested_outcome, result_state, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCEEDED', 'APPLIED', ?)
        """,
        callback.callbackEventId(),
        callback.callbackIdempotencyKey(),
        callback.attemptId(),
        callback.callbackCorrelationId(),
        callback.sandboxId(),
        callback.supportSessionId(),
        callback.traceId(),
        callback.operationId(),
        callback.intentHash(),
        Timestamp.from(createdAt));
  }

  public void insertPaymentAuditReference(
      String auditReferenceId, CallbackRecord callback, long entityVersion, Instant createdAt) {
    EvaluationAuditReferenceWriter.insert(
        jdbc,
        auditReferenceId,
        callback.sandboxId(),
        callback.supportSessionId(),
        callback.traceId(),
        callback.operationId(),
        EvaluationAuditEntityType.PAYMENT_CALLBACK,
        callback.callbackEventId(),
        entityVersion,
        createdAt);
  }

  public Instant monotonicEvaluationAuditCreatedAt(String sandboxId, Instant candidate) {
    return EvaluationAuditReferenceWriter.monotonicCreatedAt(jdbc, sandboxId, candidate);
  }

  public boolean hasPaymentMovement(AttemptRecord attempt, OrderTruth order) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM inventory_ledger
            WHERE business_event_key = ? AND movement_type = ? AND order_id = ?
              AND reservation_id <=> ? AND activity_id <=> ? AND product_id = ?
              AND sandbox_id <=> ? AND inventory_delta = 0 AND activity_quota_delta = 0
              AND payment_amount_minor = ? AND payment_currency = ?
            """,
            Integer.class,
            "mock-payment:" + attempt.attemptId(),
            order.orderKind() + "_PAYMENT",
            order.orderId(),
            order.reservationId(),
            order.activityId(),
            order.productId(),
            attempt.sandboxId(),
            attempt.amountMinor(),
            attempt.currency());
    return count != null && count == 1;
  }

  public int evaluationPaymentMovementFaceCardinality(String orderId) {
    String ledgerTable =
        EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.LEDGER);
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + ledgerTable + " WHERE order_id = ?", Integer.class, orderId);
    return count == null ? 0 : count;
  }

  public boolean hasPaymentAuditReference(CallbackRecord callback, long entityVersion) {
    String referenceId =
        EvaluationAuditReferenceIdentity.paymentCallback(
            callback.sandboxId(),
            callback.supportSessionId(),
            callback.traceId(),
            callback.operationId(),
            callback.callbackEventId(),
            entityVersion);
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM %s audit
            JOIN %s callback
              ON callback.callback_event_id = audit.entity_id
            JOIN %s attempt ON attempt.attempt_id = callback.attempt_id
            WHERE audit.audit_reference_id = ?
              AND audit.sandbox_id = ?
              AND audit.support_session_id = ?
              AND audit.trace_id = ?
              AND audit.operation_id = ?
              AND audit.entity_type = 'PAYMENT_CALLBACK'
              AND audit.entity_id = ?
              AND audit.entity_version = ?
              AND audit.outcome = 'OBSERVED'
              AND audit.created_at_anchor = 'BUSINESS_EVENT'
              AND audit.created_at = callback.created_at
              AND audit.created_at = attempt.succeeded_at
              AND NOT EXISTS (
                SELECT 1 FROM %s peer
                WHERE peer.sandbox_id = audit.sandbox_id
                  AND (
                    (peer.sequence_id < audit.sequence_id
                      AND peer.created_at > audit.created_at)
                    OR
                    (peer.sequence_id > audit.sequence_id
                      AND peer.created_at < audit.created_at)
                  )
              )
            """
                .formatted(
                    EvaluationPaymentCommittedFaces.onlyTable(
                        EvaluationPaymentCommittedFaces.AUDIT),
                    callbackTable(),
                    attemptTable(),
                    EvaluationPaymentCommittedFaces.onlyTable(
                        EvaluationPaymentCommittedFaces.AUDIT)),
            Integer.class,
            referenceId,
            callback.sandboxId(),
            callback.supportSessionId(),
            callback.traceId(),
            callback.operationId(),
            callback.callbackEventId(),
            entityVersion);
    return count != null && count == 1;
  }

  public int evaluationPaymentAuditFaceCardinality(
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String callbackEventId) {
    String auditTable =
        EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.AUDIT);
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM "
                + auditTable
                + " WHERE entity_id = ? OR (sandbox_id = ? AND support_session_id = ? "
                + "AND trace_id = ? AND operation_id = ?)",
            Integer.class,
            callbackEventId,
            sandboxId,
            supportSessionId,
            traceId,
            operationId);
    return count == null ? 0 : count;
  }

  List<AttemptRecord> enumerateAttemptClosure(AttemptRecord target, String lockClause) {
    List<String> keys = EvaluationPaymentCommittedFaces.ATTEMPT.enumerationKeys();
    requireEnumerationKeys(keys, "attempt_id", "callback_correlation_id", "order_id");
    return jdbc.query(
        "SELECT "
            + attemptColumns()
            + " FROM "
            + attemptTable()
            + " WHERE "
            + keys.get(0)
            + " = ? OR "
            + keys.get(1)
            + " = ? OR "
            + keys.get(2)
            + " = ?"
            + lockClause,
        MockPaymentRepository::mapAttempt,
        target.attemptId(),
        target.callbackCorrelationId(),
        target.orderId());
  }

  List<AttemptRecord> enumerateAttemptReplayClosure(
      String callbackCorrelationId, String orderId, String lockClause) {
    List<String> keys = EvaluationPaymentCommittedFaces.ATTEMPT.enumerationKeys();
    requireEnumerationKeys(keys, "attempt_id", "callback_correlation_id", "order_id");
    return jdbc.query(
        "SELECT "
            + attemptColumns()
            + " FROM "
            + attemptTable()
            + " WHERE "
            + keys.get(1)
            + " = ? OR "
            + keys.get(2)
            + " = ?"
            + lockClause,
        MockPaymentRepository::mapAttempt,
        callbackCorrelationId,
        orderId);
  }

  List<AttemptRecord> enumerateAttemptByOrderClosure(String orderId, String lockClause) {
    List<String> keys = EvaluationPaymentCommittedFaces.ATTEMPT.enumerationKeys();
    requireEnumerationKeys(keys, "attempt_id", "callback_correlation_id", "order_id");
    return jdbc.query(
        "SELECT "
            + attemptColumns()
            + " FROM "
            + attemptTable()
            + " WHERE "
            + keys.get(2)
            + " = ?"
            + lockClause,
        MockPaymentRepository::mapAttempt,
        orderId);
  }

  List<CallbackRecord> discoverCallbackClosure(AttemptRecord target, String lockClause) {
    List<String> relations = EvaluationPaymentCommittedFaces.CALLBACK.relationKeys();
    if (!relations.equals(List.of("attempt_id"))) {
      throw new IllegalStateException("Callback relation-key inventory is unsupported");
    }
    String correlation = EvaluationPaymentCommittedFaces.CALLBACK.stableKeys().getFirst();
    return jdbc.query(
        "SELECT "
            + callbackColumns()
            + " FROM "
            + callbackTable()
            + " WHERE "
            + correlation
            + " = ? OR "
            + relations.getFirst()
            + " = ?"
            + lockClause,
        MockPaymentRepository::mapCallback,
        target.callbackCorrelationId(),
        target.attemptId());
  }

  List<CallbackRecord> enumerateCallbackClosure(
      AttemptRecord target, CallbackRecord canonical, String lockClause) {
    List<String> keys = EvaluationPaymentCommittedFaces.CALLBACK.enumerationKeys();
    requireEnumerationKeys(
        keys,
        "callback_correlation_id",
        "callback_event_id",
        "callback_idempotency_key",
        "attempt_id");
    return jdbc.query(
        "SELECT "
            + callbackColumns()
            + " FROM "
            + callbackTable()
            + " WHERE "
            + keys.get(0)
            + " = ? OR "
            + keys.get(1)
            + " = ? OR "
            + keys.get(2)
            + " = ? OR "
            + keys.get(3)
            + " = ?"
            + lockClause,
        MockPaymentRepository::mapCallback,
        target.callbackCorrelationId(),
        canonical.callbackEventId(),
        canonical.callbackIdempotencyKey(),
        target.attemptId());
  }

  List<CallbackRecord> enumerateCallbackReplayClosure(
      AttemptRecord target,
      String callbackIdempotencyKey,
      MockPaymentCallbackRequest request,
      String lockClause) {
    List<String> keys = EvaluationPaymentCommittedFaces.CALLBACK.enumerationKeys();
    requireEnumerationKeys(
        keys,
        "callback_correlation_id",
        "callback_event_id",
        "callback_idempotency_key",
        "attempt_id");
    String relationPredicate = target == null ? "" : " OR " + keys.get(3) + " = ?";
    java.util.ArrayList<Object> arguments =
        new java.util.ArrayList<>(
            List.of(
                request.callbackCorrelationId(),
                request.callbackEventId(),
                callbackIdempotencyKey));
    if (target != null) {
      arguments.add(target.attemptId());
    }
    return jdbc.query(
        "SELECT "
            + callbackColumns()
            + " FROM "
            + callbackTable()
            + " WHERE "
            + keys.get(0)
            + " = ? OR "
            + keys.get(1)
            + " = ? OR "
            + keys.get(2)
            + " = ?"
            + relationPredicate
            + lockClause,
        MockPaymentRepository::mapCallback,
        arguments.toArray());
  }

  List<PaymentLedgerRecord> enumerateLedgerClosure(
      AttemptRecord target, OrderTruth order, String lockClause) {
    List<String> keys = EvaluationPaymentCommittedFaces.LEDGER.enumerationKeys();
    requireEnumerationKeys(keys, "business_event_key", "order_id");
    return jdbc.query(
        "SELECT "
            + EvaluationPaymentCommittedFaces.columnsCsv(EvaluationPaymentCommittedFaces.LEDGER)
            + " FROM "
            + EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.LEDGER)
            + " WHERE "
            + keys.get(0)
            + " = ? OR "
            + keys.get(1)
            + " = ?"
            + lockClause,
        MockPaymentRepository::mapLedger,
        "mock-payment:" + target.attemptId(),
        order.orderId());
  }

  List<PaymentLedgerRecord> enumerateLedgerReplayClosure(
      AttemptRecord target, String orderId, String lockClause) {
    List<String> keys = EvaluationPaymentCommittedFaces.LEDGER.enumerationKeys();
    requireEnumerationKeys(keys, "business_event_key", "order_id");
    String identityPredicate = target == null ? "" : keys.get(0) + " = ? OR ";
    java.util.ArrayList<Object> arguments = new java.util.ArrayList<>();
    if (target != null) {
      arguments.add("mock-payment:" + target.attemptId());
    }
    arguments.add(orderId);
    return jdbc.query(
        "SELECT "
            + EvaluationPaymentCommittedFaces.columnsCsv(EvaluationPaymentCommittedFaces.LEDGER)
            + " FROM "
            + EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.LEDGER)
            + " WHERE "
            + identityPredicate
            + keys.get(1)
            + " = ?"
            + lockClause,
        MockPaymentRepository::mapLedger,
        arguments.toArray());
  }

  List<PaymentAuditRecord> enumerateAuditClosure(
      CallbackRecord callback, long entityVersion, String lockClause) {
    List<String> keys = EvaluationPaymentCommittedFaces.AUDIT.enumerationKeys();
    requireEnumerationKeys(
        keys,
        "audit_reference_id",
        "entity_id",
        "sandbox_id+support_session_id+trace_id+operation_id");
    String referenceId =
        callback.sandboxId() == null
            ? ""
            : EvaluationAuditReferenceIdentity.paymentCallback(
                callback.sandboxId(),
                callback.supportSessionId(),
                callback.traceId(),
                callback.operationId(),
                callback.callbackEventId(),
                entityVersion);
    return jdbc.query(
        "SELECT "
            + EvaluationPaymentCommittedFaces.columnsCsv(EvaluationPaymentCommittedFaces.AUDIT)
            + " FROM "
            + EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.AUDIT)
            + " WHERE "
            + keys.get(0)
            + " = ? OR "
            + keys.get(1)
            + " = ? OR (sandbox_id <=> ? AND support_session_id <=> ? "
            + "AND trace_id <=> ? AND operation_id <=> ?)"
            + lockClause,
        MockPaymentRepository::mapAudit,
        referenceId,
        callback.callbackEventId(),
        callback.sandboxId(),
        callback.supportSessionId(),
        callback.traceId(),
        callback.operationId());
  }

  List<PaymentAuditRecord> enumerateAuditReplayClosure(
      MockPaymentCallbackRequest request, String lockClause) {
    List<String> keys = EvaluationPaymentCommittedFaces.AUDIT.enumerationKeys();
    requireEnumerationKeys(
        keys,
        "audit_reference_id",
        "entity_id",
        "sandbox_id+support_session_id+trace_id+operation_id");
    String referenceId =
        request.sandboxId() == null
            ? ""
            : EvaluationAuditReferenceIdentity.paymentCallback(
                request.sandboxId(),
                request.supportSessionId(),
                request.traceId(),
                request.operationId(),
                request.callbackEventId(),
                2);
    return jdbc.query(
        "SELECT "
            + EvaluationPaymentCommittedFaces.columnsCsv(EvaluationPaymentCommittedFaces.AUDIT)
            + " FROM "
            + EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.AUDIT)
            + " WHERE "
            + keys.get(0)
            + " = ? OR "
            + keys.get(1)
            + " = ? OR (sandbox_id <=> ? AND support_session_id <=> ? "
            + "AND trace_id <=> ? AND operation_id <=> ?)"
            + lockClause,
        MockPaymentRepository::mapAudit,
        referenceId,
        request.callbackEventId(),
        request.sandboxId(),
        request.supportSessionId(),
        request.traceId(),
        request.operationId());
  }

  Optional<RefundMovementAnchor> refundMovementAnchor(String refundId, String lockClause) {
    List<RefundMovementAnchor> rows =
        jdbc.query(
            """
            SELECT refund_id, payment_attempt_id, order_id, order_kind, user_subject,
                   requested_amount_minor, refunded_amount_minor, currency, state
            FROM mock_refund
            WHERE refund_id = ?
            """
                + lockClause,
            (result, row) ->
                new RefundMovementAnchor(
                    result.getString("refund_id"),
                    result.getString("payment_attempt_id"),
                    result.getString("order_id"),
                    result.getString("order_kind"),
                    result.getString("user_subject"),
                    result.getLong("requested_amount_minor"),
                    result.getLong("refunded_amount_minor"),
                    result.getString("currency"),
                    result.getString("state")),
            refundId);
    if (rows.size() > 1) {
      throw new MockPaymentIntegrityException("Refund movement anchor is not unique");
    }
    return rows.stream().findFirst();
  }

  boolean auditSequenceOrderConsistent(String sandboxId) {
    Integer contradictions =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM eval_commerce_audit_reference audit
            WHERE audit.sandbox_id = ?
              AND EXISTS (
                SELECT 1 FROM eval_commerce_audit_reference peer
                WHERE peer.sandbox_id = audit.sandbox_id
                  AND (
                    (peer.sequence_id < audit.sequence_id
                      AND peer.created_at > audit.created_at)
                    OR
                    (peer.sequence_id > audit.sequence_id
                      AND peer.created_at < audit.created_at)
                  )
              )
            """,
            Integer.class,
            sandboxId);
    return contradictions != null && contradictions == 0;
  }

  private static void requireEnumerationKeys(List<String> actual, String... expected) {
    if (!actual.equals(List.of(expected))) {
      throw new IllegalStateException("Committed payment enumeration-key inventory changed");
    }
  }

  private Optional<AttemptRecord> queryAttempt(String sql, Object... arguments) {
    List<AttemptRecord> rows = jdbc.query(sql, MockPaymentRepository::mapAttempt, arguments);
    if (rows.size() > 1) {
      throw new MockPaymentIntegrityException("Payment attempt uniqueness is corrupted");
    }
    return rows.stream().findFirst();
  }

  private Optional<CallbackRecord> queryCallback(String sql, Object... arguments) {
    List<CallbackRecord> rows = jdbc.query(sql, MockPaymentRepository::mapCallback, arguments);
    if (rows.size() > 1) {
      throw new MockPaymentIntegrityException("Payment callback uniqueness is corrupted");
    }
    return rows.stream().findFirst();
  }

  private static AttemptRecord mapAttempt(java.sql.ResultSet result, int row)
      throws java.sql.SQLException {
    Timestamp succeededAt = result.getTimestamp("succeeded_at");
    return new AttemptRecord(
        result.getString("attempt_id"),
        result.getString("callback_correlation_id"),
        result.getString("user_subject"),
        result.getString("order_id"),
        result.getString("order_kind"),
        result.getString("sandbox_id"),
        result.getString("request_idempotency_key"),
        result.getString("intent_hash"),
        result.getLong("amount_minor"),
        result.getLong("refunded_amount_minor"),
        result.getString("currency"),
        result.getString("state"),
        result.getLong("state_version"),
        succeededAt == null ? null : succeededAt.toInstant());
  }

  private static CallbackRecord mapCallback(java.sql.ResultSet result, int row)
      throws java.sql.SQLException {
    return new CallbackRecord(
        result.getString("callback_event_id"),
        result.getString("callback_idempotency_key"),
        result.getString("attempt_id"),
        result.getString("callback_correlation_id"),
        result.getString("sandbox_id"),
        result.getString("support_session_id"),
        result.getString("trace_id"),
        result.getString("operation_id"),
        result.getString("intent_hash"),
        result.getString("requested_outcome"),
        result.getString("result_state"),
        result.getTimestamp("created_at").toInstant());
  }

  private static OrderTruth mapOrder(java.sql.ResultSet result, String kind)
      throws java.sql.SQLException {
    return new OrderTruth(
        kind,
        result.getString("order_id"),
        result.getString("user_subject"),
        result.getString("sandbox_id"),
        result.getString("evaluation_owner_handle"),
        result.getString("product_id"),
        result.getString("reservation_id"),
        result.getString("activity_id"),
        result.getLong("total_price_minor"),
        result.getString("currency"),
        result.getString("status"),
        result.getLong("state_version"));
  }

  private static PaymentLedgerRecord mapLedger(java.sql.ResultSet result, int row)
      throws java.sql.SQLException {
    return new PaymentLedgerRecord(
        result.getString("movement_id"),
        result.getString("business_event_key"),
        result.getString("movement_type"),
        result.getString("order_id"),
        result.getString("reservation_id"),
        result.getString("activity_id"),
        result.getString("product_id"),
        result.getString("sandbox_id"),
        result.getLong("inventory_delta"),
        result.getLong("activity_quota_delta"),
        result.getObject("payment_amount_minor", Long.class),
        result.getString("payment_currency"));
  }

  private static PaymentAuditRecord mapAudit(java.sql.ResultSet result, int row)
      throws java.sql.SQLException {
    return new PaymentAuditRecord(
        result.getLong("sequence_id"),
        result.getString("audit_reference_id"),
        result.getString("sandbox_id"),
        result.getString("support_session_id"),
        result.getString("trace_id"),
        result.getString("operation_id"),
        result.getString("entity_type"),
        result.getString("entity_id"),
        result.getLong("entity_version"),
        result.getString("outcome"),
        result.getTimestamp("created_at").toInstant(),
        result.getString("created_at_anchor"));
  }

  private static String attemptColumns() {
    return EvaluationPaymentCommittedFaces.columnsCsv(EvaluationPaymentCommittedFaces.ATTEMPT);
  }

  private static String attemptTable() {
    return EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.ATTEMPT);
  }

  private static String callbackColumns() {
    return EvaluationPaymentCommittedFaces.columnsCsv(EvaluationPaymentCommittedFaces.CALLBACK);
  }

  private static String callbackTable() {
    return EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.CALLBACK);
  }

  public record OrderTruth(
      String orderKind,
      String orderId,
      String userSubject,
      String sandboxId,
      String evaluationOwnerHandle,
      String productId,
      String reservationId,
      String activityId,
      long amountMinor,
      String currency,
      String status,
      long stateVersion) {}

  public record AttemptRecord(
      String attemptId,
      String callbackCorrelationId,
      String userSubject,
      String orderId,
      String orderKind,
      String sandboxId,
      String requestIdempotencyKey,
      String intentHash,
      long amountMinor,
      long refundedAmountMinor,
      String currency,
      String state,
      long stateVersion,
      Instant succeededAt) {
    static AttemptRecord pending(
        String attemptId,
        String callbackCorrelationId,
        String userSubject,
        String orderId,
        String orderKind,
        String sandboxId,
        String requestIdempotencyKey,
        String intentHash,
        long amountMinor,
        String currency) {
      return new AttemptRecord(
          attemptId,
          callbackCorrelationId,
          userSubject,
          orderId,
          orderKind,
          sandboxId,
          requestIdempotencyKey,
          intentHash,
          amountMinor,
          0,
          currency,
          "PENDING",
          1,
          null);
    }
  }

  public record CallbackRecord(
      String callbackEventId,
      String callbackIdempotencyKey,
      String attemptId,
      String callbackCorrelationId,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String intentHash,
      String requestedOutcome,
      String resultState,
      Instant createdAt) {}

  public record PaymentLedgerRecord(
      String movementId,
      String businessEventKey,
      String movementType,
      String orderId,
      String reservationId,
      String activityId,
      String productId,
      String sandboxId,
      long inventoryDelta,
      long activityQuotaDelta,
      Long paymentAmountMinor,
      String paymentCurrency) {}

  public record PaymentAuditRecord(
      long sequenceId,
      String auditReferenceId,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String entityType,
      String entityId,
      long entityVersion,
      String outcome,
      Instant createdAt,
      String createdAtAnchor) {}

  record RefundMovementAnchor(
      String refundId,
      String paymentAttemptId,
      String orderId,
      String orderKind,
      String userSubject,
      long requestedAmountMinor,
      long refundedAmountMinor,
      String currency,
      String state) {}
}
