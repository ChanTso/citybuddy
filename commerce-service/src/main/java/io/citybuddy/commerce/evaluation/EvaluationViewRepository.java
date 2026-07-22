package io.citybuddy.commerce.evaluation;

import io.citybuddy.commerce.payment.EvaluationPaymentCommittedFaces;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

public final class EvaluationViewRepository {
  private static final Set<EvaluationAuditEntityType> RECONCILED_AUDIT_TYPES =
      Collections.unmodifiableSet(
          EnumSet.of(
              EvaluationAuditEntityType.PRODUCT_FIXTURE,
              EvaluationAuditEntityType.PAYMENT_CALLBACK));

  private final JdbcTemplate jdbc;

  public EvaluationViewRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void validateSchema() {
    jdbc.query(
        """
        SELECT sandbox_id, lifecycle_state, auth_invalidation_state, death_reason,
               fixture_count, expires_at, activated_at, dead_at, closed_at, version
        FROM eval_sandbox WHERE 1 = 0
        """,
        result -> null);
    jdbc.query(
        """
        SELECT attempt_id, callback_correlation_id, order_id, sandbox_id, amount_minor,
               currency, state, state_version
        FROM mock_payment_attempt WHERE 1 = 0
        """,
        result -> null);
    jdbc.query(
        """
        SELECT callback_event_id, attempt_id, sandbox_id, operation_id
        FROM mock_payment_callback WHERE 1 = 0
        """,
        result -> null);
    jdbc.query(
        """
        SELECT sandbox_id, product_id, name, description, price_minor, currency,
               stock_quantity, available, publication_version
        FROM eval_sandbox_product_fixture WHERE 1 = 0
        """,
        result -> null);
    jdbc.query(
        """
        SELECT sandbox_id, effect_type, outcome, created_at
        FROM eval_sandbox_effect_stub WHERE 1 = 0
        """,
        result -> null);
    jdbc.query(
        """
        SELECT audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id,
               entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor
        FROM eval_commerce_audit_reference WHERE 1 = 0
        """,
        result -> null);
    jdbc.query(
        """
        SELECT observation_id, sandbox_id, support_session_id, trace_id, operation_id,
               product_id, product_version, outcome, created_at
        FROM eval_commerce_product_observation WHERE 1 = 0
        """,
        result -> null);
    jdbc.query(
        """
        SELECT watermark_key, commitment_format, legacy_set_digest, cutoff_sequence_id,
               cutoff_audit_reference_id, cutoff_created_at, legacy_row_count, recorded_at
        FROM eval_commerce_audit_legacy_watermark WHERE 1 = 0
        """,
        result -> null);
  }

  public Optional<SandboxView> sandbox(String sandboxId) {
    return jdbc
        .query(
            """
            SELECT sandbox_id, lifecycle_state, auth_invalidation_state, death_reason,
                   fixture_count, expires_at, activated_at, dead_at, closed_at, version
            FROM eval_sandbox WHERE sandbox_id = ?
            """,
            EvaluationViewRepository::mapSandbox,
            sandboxId)
        .stream()
        .findFirst();
  }

  public List<ProductView> products(String sandboxId) {
    return jdbc.query(
        """
        SELECT product_id, name, description, price_minor, currency, stock_quantity,
               available, publication_version
        FROM eval_sandbox_product_fixture
        WHERE sandbox_id = ? ORDER BY product_id LIMIT 16
        """,
        (result, row) ->
            new ProductView(
                result.getString("product_id"),
                result.getString("name"),
                result.getString("description"),
                result.getLong("price_minor"),
                result.getString("currency"),
                result.getLong("stock_quantity"),
                result.getBoolean("available"),
                result.getLong("publication_version")),
        sandboxId);
  }

  public List<EffectView> effects(String sandboxId) {
    return jdbc.query(
        """
        SELECT effect_type, outcome, created_at
        FROM eval_sandbox_effect_stub
        WHERE sandbox_id = ? ORDER BY created_at, effect_type, correlation_key LIMIT 8
        """,
        (result, row) ->
            new EffectView(
                result.getString("effect_type"),
                result.getString("outcome"),
                result.getTimestamp("created_at").toInstant()),
        sandboxId);
  }

  public List<PaymentView> payments(String sandboxId) {
    return jdbc.query(
        paymentViewSql(),
        (result, row) ->
            new PaymentView(
                result.getString("attempt_id"),
                result.getString("callback_correlation_id"),
                result.getString("order_id"),
                result.getLong("amount_minor"),
                result.getString("currency"),
                result.getString("state"),
                result.getLong("state_version"),
                result.getString("callback_event_id"),
                result.getString("order_status"),
                result.getLong("order_state_version"),
                result.getInt("movement_count")),
        sandboxId);
  }

  private static String paymentViewSql() {
    return """
        SELECT a.attempt_id, a.callback_correlation_id, a.order_id, a.amount_minor, a.currency,
               a.state, a.state_version,
               CASE
                 WHEN c.callback_event_id IS NULL THEN NULL
                 WHEN c.sandbox_id = a.sandbox_id
                   AND c.callback_correlation_id = a.callback_correlation_id
                   AND c.requested_outcome = 'SUCCEEDED' AND c.result_state = 'APPLIED'
                   AND c.support_session_id IS NOT NULL AND c.trace_id IS NOT NULL
                   AND c.operation_id IS NOT NULL
                   AND c.intent_hash = SHA2(CONCAT_WS('\n', c.callback_event_id,
                     c.callback_correlation_id, a.order_id, a.amount_minor, a.currency,
                     c.requested_outcome, c.sandbox_id, c.support_session_id, c.trace_id,
                     c.operation_id, c.callback_idempotency_key), 256)
                   THEN c.callback_event_id
                 ELSE 'INCONSISTENT'
               END AS callback_event_id,
               CASE
                 WHEN a.order_kind = 'STANDARD' AND o.sandbox_id = a.sandbox_id
                   AND o.user_subject = a.user_subject
                   AND o.total_price_minor = a.amount_minor AND o.currency = a.currency
                   THEN o.status
                 ELSE 'INCONSISTENT'
               END AS order_status,
               CASE
                 WHEN a.order_kind = 'STANDARD' AND o.sandbox_id = a.sandbox_id
                   AND o.user_subject = a.user_subject
                   AND o.total_price_minor = a.amount_minor AND o.currency = a.currency
                   THEN o.state_version
                 ELSE 0
               END AS order_state_version,
               CASE
                 WHEN (SELECT COUNT(*) FROM %s l
                       WHERE l.business_event_key = CONCAT('mock-payment:', a.attempt_id))
                    = (SELECT COUNT(*) FROM %s l
                       WHERE l.business_event_key = CONCAT('mock-payment:', a.attempt_id)
                         AND l.movement_type = 'STANDARD_PAYMENT' AND l.order_id = a.order_id
                         AND l.reservation_id IS NULL AND l.activity_id IS NULL
                         AND l.product_id = o.product_id AND l.sandbox_id = a.sandbox_id
                         AND l.inventory_delta = 0 AND l.activity_quota_delta = 0
                         AND l.payment_amount_minor = a.amount_minor
                         AND l.payment_currency = a.currency)
                   THEN (SELECT COUNT(*) FROM %s l
                         WHERE l.business_event_key = CONCAT('mock-payment:', a.attempt_id))
                 ELSE -1
               END AS movement_count
        FROM %s a
        LEFT JOIN (%s) o ON o.order_id = a.order_id
        LEFT JOIN %s c
          ON c.attempt_id = a.attempt_id
        WHERE a.sandbox_id = ?
        ORDER BY a.created_at, a.attempt_id
        LIMIT 8
        """
        .formatted(
            EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.LEDGER),
            EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.LEDGER),
            EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.LEDGER),
            EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.ATTEMPT),
            EvaluationPaymentCommittedFaces.orderFaceUnionSql(),
            EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.CALLBACK));
  }

  public List<AuditReference> audit(
      String sandboxId, String supportSessionId, long after, int fetchLimit) {
    return jdbc.query(
        """
        SELECT sequence_id, audit_reference_id, sandbox_id, support_session_id, trace_id,
               operation_id, entity_type, entity_id, entity_version, outcome, created_at
        FROM eval_commerce_audit_reference
        WHERE sandbox_id = ? AND support_session_id = ? AND sequence_id > ?
        ORDER BY sequence_id LIMIT ?
        """,
        EvaluationViewRepository::mapAudit,
        sandboxId,
        supportSessionId,
        after,
        fetchLimit);
  }

  public boolean auditReferencesConsistent(String sandboxId) {
    List<IntegrityAuditReference> references = allAuditReferences(sandboxId);
    List<ProductObservationTruth> productTruths = productObservationTruths(sandboxId);
    if (!EvaluationLegacyAuditCommitmentStore.load(jdbc).isConsistent()
        || !sequenceOrderConsistent(references)
        || !paymentFaceCardinalitiesConsistent(sandboxId)
        || !paymentTruthsConsistent(
            references,
            paidOrderTruths(sandboxId),
            paymentLedgerTruths(sandboxId),
            succeededCallbackTruths(sandboxId))) {
      return false;
    }
    for (ProductObservationTruth truth : productTruths) {
      if (!productObservationIsAuthoritative(truth)
          || references.stream()
                  .filter(reference -> matches(reference, truth) || matchesLegacy(reference, truth))
                  .count()
              != 1) {
        return false;
      }
    }
    for (IntegrityAuditReference reference : references) {
      Optional<EvaluationAuditEntityType> storedType =
          EvaluationAuditEntityType.fromStored(reference.entityType());
      if (storedType.isEmpty()
          || (!"BUSINESS_EVENT".equals(reference.createdAtAnchor())
              && !"LEGACY_CUTOFF".equals(reference.createdAtAnchor()))) {
        return false;
      }
      boolean matched;
      if ("LEGACY_CUTOFF".equals(reference.createdAtAnchor())) {
        matched =
            storedType.get() == EvaluationAuditEntityType.PRODUCT_FIXTURE
                && legacyProductReferenceConsistent(reference, productTruths);
      } else {
        matched =
            switch (storedType.get()) {
              case PRODUCT_FIXTURE ->
                  productTruths.stream().filter(truth -> matches(reference, truth)).count() == 1;
              case PAYMENT_CALLBACK -> true;
            };
      }
      if (!matched) {
        return false;
      }
    }
    return true;
  }

  private boolean paymentFaceCardinalitiesConsistent(String sandboxId) {
    String callbackTable =
        EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.CALLBACK);
    String callbackKey = EvaluationPaymentCommittedFaces.CALLBACK.stableKeys().getFirst();
    String attemptTable =
        EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.ATTEMPT);
    String attemptKey = EvaluationPaymentCommittedFaces.ATTEMPT.stableKeys().getFirst();
    String ledgerTable =
        EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.LEDGER);
    String auditTable =
        EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.AUDIT);
    return duplicateGroupCount(
                "SELECT "
                    + callbackKey
                    + " FROM "
                    + callbackTable
                    + " WHERE "
                    + callbackKey
                    + " IN (SELECT "
                    + callbackKey
                    + " FROM "
                    + callbackTable
                    + " WHERE sandbox_id = ?) GROUP BY "
                    + callbackKey
                    + " HAVING COUNT(*) > 1",
                sandboxId)
            == 0
        && duplicateGroupCount(
                "SELECT "
                    + attemptKey
                    + " FROM "
                    + attemptTable
                    + " WHERE "
                    + attemptKey
                    + " IN (SELECT "
                    + attemptKey
                    + " FROM "
                    + attemptTable
                    + " WHERE sandbox_id = ?) GROUP BY "
                    + attemptKey
                    + " HAVING COUNT(*) > 1",
                sandboxId)
            == 0
        && duplicateGroupCount(
                "SELECT order_id FROM ("
                    + EvaluationPaymentCommittedFaces.orderFaceUnionSql()
                    + ") committed_order WHERE order_id IN ("
                    + EvaluationPaymentCommittedFaces.evaluationOrderKeysBySandboxSql()
                    + ") GROUP BY order_id HAVING COUNT(*) > 1",
                sandboxId)
            == 0
        && duplicateGroupCount(
                "SELECT order_id FROM "
                    + ledgerTable
                    + " WHERE order_id IN ("
                    + EvaluationPaymentCommittedFaces.evaluationOrderKeysBySandboxSql()
                    + ") GROUP BY order_id HAVING COUNT(*) > 1",
                sandboxId)
            == 0
        && duplicateGroupCount(
                "SELECT entity_id FROM "
                    + auditTable
                    + " WHERE entity_id IN (SELECT callback_event_id FROM "
                    + callbackTable
                    + " WHERE sandbox_id = ?) GROUP BY entity_id HAVING COUNT(*) > 1",
                sandboxId)
            == 0;
  }

  private int duplicateGroupCount(String groupedQuery, String sandboxId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM (" + groupedQuery + ") duplicate_groups",
            Integer.class,
            sandboxId);
    return count == null ? 0 : count;
  }

  public static Set<EvaluationAuditEntityType> reconciledAuditTypes() {
    return RECONCILED_AUDIT_TYPES;
  }

  private List<IntegrityAuditReference> allAuditReferences(String sandboxId) {
    return jdbc.query(
        """
        SELECT sequence_id, audit_reference_id, sandbox_id, support_session_id, trace_id,
               operation_id, entity_type, entity_id, entity_version, outcome, created_at,
               created_at_anchor
        FROM eval_commerce_audit_reference
        WHERE sandbox_id = ?
        ORDER BY sequence_id
        """,
        EvaluationViewRepository::mapIntegrityAudit,
        sandboxId);
  }

  private List<ProductObservationTruth> productObservationTruths(String sandboxId) {
    return jdbc.query(
        """
        SELECT observation_id, sandbox_id, support_session_id, trace_id, operation_id,
               product_id, product_version, outcome, created_at
        FROM eval_commerce_product_observation
        WHERE sandbox_id = ?
        ORDER BY observation_id
        """,
        (result, row) ->
            new ProductObservationTruth(
                result.getString("observation_id"),
                result.getString("sandbox_id"),
                result.getString("support_session_id"),
                result.getString("trace_id"),
                result.getString("operation_id"),
                result.getString("product_id"),
                result.getLong("product_version"),
                result.getString("outcome"),
                result.getTimestamp("created_at").toInstant()),
        sandboxId);
  }

  private List<PaidOrderTruth> paidOrderTruths(String sandboxId) {
    return jdbc.query(
        """
        SELECT order_kind, order_id, sandbox_id, user_subject, product_id, total_price_minor,
               currency, status, state_version
        FROM (%s) committed_order
        WHERE order_id IN (%s) AND status = 'PAID'
        ORDER BY order_id
        """
            .formatted(
                EvaluationPaymentCommittedFaces.orderFaceUnionSql(),
                EvaluationPaymentCommittedFaces.evaluationOrderKeysBySandboxSql()),
        (result, row) ->
            new PaidOrderTruth(
                result.getString("order_kind"),
                result.getString("order_id"),
                result.getString("sandbox_id"),
                result.getString("user_subject"),
                result.getString("product_id"),
                result.getLong("total_price_minor"),
                result.getString("currency"),
                result.getString("status"),
                result.getLong("state_version")),
        sandboxId);
  }

  private List<PaymentLedgerTruth> paymentLedgerTruths(String sandboxId) {
    return jdbc.query(
        """
        SELECT movement_id, business_event_key, movement_type, order_id, reservation_id,
               activity_id, product_id, sandbox_id, inventory_delta, activity_quota_delta,
               payment_amount_minor, payment_currency
        FROM %s
        WHERE order_id IN (%s)
        ORDER BY order_id, movement_id
        """
            .formatted(
                EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.LEDGER),
                EvaluationPaymentCommittedFaces.evaluationOrderKeysBySandboxSql()),
        (result, row) ->
            new PaymentLedgerTruth(
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
                result.getString("payment_currency")),
        sandboxId);
  }

  private List<SucceededCallbackTruth> succeededCallbackTruths(String sandboxId) {
    return jdbc.query(
        """
        SELECT c.callback_event_id, c.callback_idempotency_key, c.attempt_id,
               c.callback_correlation_id, c.sandbox_id, c.support_session_id, c.trace_id,
               c.operation_id, c.intent_hash, c.requested_outcome, c.result_state, c.created_at,
               a.callback_correlation_id AS attempt_correlation_id,
               a.user_subject AS attempt_user_subject, a.order_id AS attempt_order_id,
               a.order_kind AS attempt_order_kind, a.sandbox_id AS attempt_sandbox_id,
               a.amount_minor AS attempt_amount_minor, a.currency AS attempt_currency,
               a.state AS attempt_state, a.state_version AS attempt_state_version
        FROM %s c
        LEFT JOIN %s a ON a.attempt_id = c.attempt_id
        WHERE c.callback_correlation_id IN (
                SELECT callback_correlation_id FROM %s WHERE sandbox_id = ?
              )
           OR c.callback_event_id IN (
                SELECT entity_id FROM %s WHERE sandbox_id = ?
              )
        ORDER BY a.order_id, c.callback_event_id
        """
            .formatted(
                EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.CALLBACK),
                EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.ATTEMPT),
                EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.ATTEMPT),
                EvaluationPaymentCommittedFaces.onlyTable(EvaluationPaymentCommittedFaces.AUDIT)),
        (result, row) ->
            new SucceededCallbackTruth(
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
                result.getTimestamp("created_at").toInstant(),
                result.getString("attempt_correlation_id"),
                result.getString("attempt_user_subject"),
                result.getString("attempt_order_id"),
                result.getString("attempt_order_kind"),
                result.getString("attempt_sandbox_id"),
                result.getLong("attempt_amount_minor"),
                result.getString("attempt_currency"),
                result.getString("attempt_state"),
                result.getLong("attempt_state_version")),
        sandboxId,
        sandboxId);
  }

  private boolean productObservationIsAuthoritative(ProductObservationTruth truth) {
    return "OBSERVED".equals(truth.outcome())
        && truth
            .observationId()
            .equals(
                EvaluationAuditReferenceIdentity.productFixture(
                    truth.sandboxId(),
                    truth.supportSessionId(),
                    truth.traceId(),
                    truth.operationId(),
                    truth.productId(),
                    truth.productVersion()));
  }

  private static boolean sequenceOrderConsistent(List<IntegrityAuditReference> references) {
    Instant previousCreatedAt = null;
    for (IntegrityAuditReference reference : references) {
      if (previousCreatedAt != null && reference.createdAt().isBefore(previousCreatedAt)) {
        return false;
      }
      previousCreatedAt = reference.createdAt();
    }
    return true;
  }

  private static boolean legacyProductReferenceConsistent(
      IntegrityAuditReference reference, List<ProductObservationTruth> productTruths) {
    if (!"OBSERVED".equals(reference.outcome())
        || !reference
            .auditReferenceId()
            .equals(
                EvaluationAuditReferenceIdentity.productFixture(
                    reference.sandboxId(),
                    reference.supportSessionId(),
                    reference.traceId(),
                    reference.operationId(),
                    reference.entityId(),
                    reference.entityVersion()))) {
      return false;
    }
    List<ProductObservationTruth> matchingTruths =
        productTruths.stream()
            .filter(truth -> truth.observationId().equals(reference.auditReferenceId()))
            .toList();
    return matchingTruths.isEmpty()
        || (matchingTruths.size() == 1 && matchesLegacy(reference, matchingTruths.getFirst()));
  }

  private static boolean matches(IntegrityAuditReference reference, ProductObservationTruth truth) {
    return reference.auditReferenceId().equals(truth.observationId())
        && reference.sandboxId().equals(truth.sandboxId())
        && reference.supportSessionId().equals(truth.supportSessionId())
        && reference.traceId().equals(truth.traceId())
        && reference.operationId().equals(truth.operationId())
        && reference.entityType().equals(EvaluationAuditEntityType.PRODUCT_FIXTURE.name())
        && reference.entityId().equals(truth.productId())
        && reference.entityVersion() == truth.productVersion()
        && reference.outcome().equals(truth.outcome())
        && reference.createdAt().equals(truth.createdAt())
        && "BUSINESS_EVENT".equals(reference.createdAtAnchor());
  }

  private static boolean matchesLegacy(
      IntegrityAuditReference reference, ProductObservationTruth truth) {
    return reference.auditReferenceId().equals(truth.observationId())
        && reference.sandboxId().equals(truth.sandboxId())
        && reference.supportSessionId().equals(truth.supportSessionId())
        && reference.traceId().equals(truth.traceId())
        && reference.operationId().equals(truth.operationId())
        && reference.entityType().equals(EvaluationAuditEntityType.PRODUCT_FIXTURE.name())
        && reference.entityId().equals(truth.productId())
        && reference.entityVersion() == truth.productVersion()
        && reference.outcome().equals(truth.outcome())
        && reference.createdAt().equals(truth.createdAt())
        && "LEGACY_CUTOFF".equals(reference.createdAtAnchor());
  }

  private static boolean paymentTruthsConsistent(
      List<IntegrityAuditReference> references,
      List<PaidOrderTruth> orders,
      List<PaymentLedgerTruth> ledgers,
      List<SucceededCallbackTruth> callbacks) {
    List<IntegrityAuditReference> paymentReferences =
        references.stream()
            .filter(
                reference ->
                    EvaluationAuditEntityType.PAYMENT_CALLBACK
                        .name()
                        .equals(reference.entityType()))
            .toList();
    Set<String> orderKeys =
        orders.stream().map(PaidOrderTruth::orderId).collect(Collectors.toSet());
    Set<String> ledgerKeys =
        ledgers.stream().map(PaymentLedgerTruth::orderId).collect(Collectors.toSet());
    Set<String> callbackKeys =
        callbacks.stream().map(SucceededCallbackTruth::attemptOrderId).collect(Collectors.toSet());
    Set<String> auditKeys =
        paymentReferences.stream()
            .map(
                reference ->
                    callbacks.stream()
                        .filter(callback -> callback.callbackEventId().equals(reference.entityId()))
                        .map(SucceededCallbackTruth::attemptOrderId)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (orderKeys.size() != orders.size()
        || ledgerKeys.size() != ledgers.size()
        || callbackKeys.size() != callbacks.size()
        || auditKeys.size() != paymentReferences.size()
        || !orderKeys.equals(ledgerKeys)
        || !orderKeys.equals(callbackKeys)
        || !orderKeys.equals(auditKeys)) {
      return false;
    }
    for (String orderId : orderKeys) {
      PaidOrderTruth order =
          orders.stream()
              .filter(value -> value.orderId().equals(orderId))
              .findFirst()
              .orElseThrow();
      PaymentLedgerTruth ledger =
          ledgers.stream()
              .filter(value -> value.orderId().equals(orderId))
              .findFirst()
              .orElseThrow();
      SucceededCallbackTruth callback =
          callbacks.stream()
              .filter(value -> value.attemptOrderId().equals(orderId))
              .findFirst()
              .orElseThrow();
      List<IntegrityAuditReference> matchingReferences =
          paymentReferences.stream()
              .filter(reference -> reference.entityId().equals(callback.callbackEventId()))
              .toList();
      if (matchingReferences.size() != 1
          || !paymentTruthIsAuthoritative(order, ledger, callback, matchingReferences.getFirst())) {
        return false;
      }
    }
    return true;
  }

  private static boolean paymentTruthIsAuthoritative(
      PaidOrderTruth order,
      PaymentLedgerTruth ledger,
      SucceededCallbackTruth callback,
      IntegrityAuditReference reference) {
    String expectedIntentHash =
        sha256(
            String.join(
                "\n",
                callback.callbackEventId(),
                callback.callbackCorrelationId(),
                callback.attemptOrderId(),
                Long.toString(callback.attemptAmountMinor()),
                callback.attemptCurrency(),
                callback.requestedOutcome(),
                callback.sandboxId(),
                callback.supportSessionId(),
                callback.traceId(),
                callback.operationId(),
                callback.callbackIdempotencyKey()));
    PaymentAuditTruth truth =
        new PaymentAuditTruth(
            callback.sandboxId(),
            callback.supportSessionId(),
            callback.traceId(),
            callback.operationId(),
            callback.callbackEventId(),
            callback.attemptStateVersion(),
            callback.createdAt());
    return "STANDARD".equals(order.orderKind())
        && "PAID".equals(order.status())
        && order.stateVersion() == 2
        && "STANDARD".equals(callback.attemptOrderKind())
        && "SUCCEEDED".equals(callback.attemptState())
        && callback.attemptStateVersion() == 2
        && "SUCCEEDED".equals(callback.requestedOutcome())
        && "APPLIED".equals(callback.resultState())
        && callback.intentHash().equals(expectedIntentHash)
        && callback.callbackCorrelationId().equals(callback.attemptCorrelationId())
        && callback.sandboxId().equals(callback.attemptSandboxId())
        && callback.sandboxId().equals(order.sandboxId())
        && callback.attemptUserSubject().equals(order.userSubject())
        && callback.attemptOrderId().equals(order.orderId())
        && callback.attemptAmountMinor() == order.totalPriceMinor()
        && callback.attemptCurrency().equals(order.currency())
        && ledger.businessEventKey().equals("mock-payment:" + callback.attemptId())
        && "STANDARD_PAYMENT".equals(ledger.movementType())
        && ledger.orderId().equals(order.orderId())
        && ledger.reservationId() == null
        && ledger.activityId() == null
        && ledger.productId().equals(order.productId())
        && ledger.sandboxId().equals(order.sandboxId())
        && ledger.inventoryDelta() == 0
        && ledger.activityQuotaDelta() == 0
        && Objects.equals(ledger.paymentAmountMinor(), order.totalPriceMinor())
        && order.currency().equals(ledger.paymentCurrency())
        && matches(reference, truth);
  }

  private static boolean matches(IntegrityAuditReference reference, PaymentAuditTruth truth) {
    return reference
            .auditReferenceId()
            .equals(
                EvaluationAuditReferenceIdentity.paymentCallback(
                    truth.sandboxId(),
                    truth.supportSessionId(),
                    truth.traceId(),
                    truth.operationId(),
                    truth.callbackEventId(),
                    truth.entityVersion()))
        && reference.sandboxId().equals(truth.sandboxId())
        && reference.supportSessionId().equals(truth.supportSessionId())
        && reference.traceId().equals(truth.traceId())
        && reference.operationId().equals(truth.operationId())
        && reference.entityType().equals(EvaluationAuditEntityType.PAYMENT_CALLBACK.name())
        && reference.entityId().equals(truth.callbackEventId())
        && reference.entityVersion() == truth.entityVersion()
        && "OBSERVED".equals(reference.outcome())
        && reference.createdAt().equals(truth.createdAt())
        && "BUSINESS_EVENT".equals(reference.createdAtAnchor());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Payment audit intent hash algorithm is unavailable", exception);
    }
  }

  private static SandboxView mapSandbox(ResultSet result, int row) throws SQLException {
    return new SandboxView(
        result.getString("sandbox_id"),
        result.getString("lifecycle_state"),
        result.getString("auth_invalidation_state"),
        result.getString("death_reason"),
        result.getInt("fixture_count"),
        instant(result, "expires_at"),
        instant(result, "activated_at"),
        instant(result, "dead_at"),
        instant(result, "closed_at"),
        result.getLong("version"));
  }

  private static AuditReference mapAudit(ResultSet result, int row) throws SQLException {
    return new AuditReference(
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
        result.getTimestamp("created_at").toInstant());
  }

  private static IntegrityAuditReference mapIntegrityAudit(ResultSet result, int row)
      throws SQLException {
    return new IntegrityAuditReference(
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

  private static Instant instant(ResultSet result, String column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private record ProductObservationTruth(
      String observationId,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String productId,
      long productVersion,
      String outcome,
      Instant createdAt) {}

  private record PaidOrderTruth(
      String orderKind,
      String orderId,
      String sandboxId,
      String userSubject,
      String productId,
      long totalPriceMinor,
      String currency,
      String status,
      long stateVersion) {}

  private record PaymentLedgerTruth(
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

  private record SucceededCallbackTruth(
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
      Instant createdAt,
      String attemptCorrelationId,
      String attemptUserSubject,
      String attemptOrderId,
      String attemptOrderKind,
      String attemptSandboxId,
      long attemptAmountMinor,
      String attemptCurrency,
      String attemptState,
      long attemptStateVersion) {}

  private record PaymentAuditTruth(
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String callbackEventId,
      long entityVersion,
      Instant createdAt) {}

  private record IntegrityAuditReference(
      long sequence,
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

  public record SandboxView(
      String sandboxId,
      String lifecycleState,
      String authInvalidationState,
      String deathReason,
      int fixtureCount,
      Instant expiresAt,
      Instant activatedAt,
      Instant deadAt,
      Instant closedAt,
      long version) {}

  public record ProductView(
      String productId,
      String name,
      String description,
      long priceMinor,
      String currency,
      long stockQuantity,
      boolean available,
      long publicationVersion) {}

  public record EffectView(String effectType, String outcome, Instant createdAt) {}

  public record PaymentView(
      String attemptId,
      String callbackCorrelationId,
      String orderId,
      long amountMinor,
      String currency,
      String state,
      long stateVersion,
      String callbackEventId,
      String orderStatus,
      long orderStateVersion,
      int movementCount) {}

  public record AuditReference(
      long sequence,
      String auditReferenceId,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String entityType,
      String entityId,
      long entityVersion,
      String outcome,
      Instant createdAt) {}
}
