package io.citybuddy.commerce.evaluation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class EvaluationViewRepository {
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
               entity_type, entity_id, entity_version, outcome, created_at
        FROM eval_commerce_audit_reference WHERE 1 = 0
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
        """
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
                 WHEN (SELECT COUNT(*) FROM inventory_ledger l
                       WHERE l.business_event_key = CONCAT('mock-payment:', a.attempt_id))
                    = (SELECT COUNT(*) FROM inventory_ledger l
                       WHERE l.business_event_key = CONCAT('mock-payment:', a.attempt_id)
                         AND l.movement_type = 'STANDARD_PAYMENT' AND l.order_id = a.order_id
                         AND l.reservation_id IS NULL AND l.activity_id IS NULL
                         AND l.product_id = o.product_id AND l.sandbox_id = a.sandbox_id
                         AND l.inventory_delta = 0 AND l.activity_quota_delta = 0
                         AND l.payment_amount_minor = a.amount_minor
                         AND l.payment_currency = a.currency)
                   THEN (SELECT COUNT(*) FROM inventory_ledger l
                         WHERE l.business_event_key = CONCAT('mock-payment:', a.attempt_id))
                 ELSE -1
               END AS movement_count
        FROM mock_payment_attempt a
        LEFT JOIN standard_order o ON o.order_id = a.order_id
        LEFT JOIN mock_payment_callback c
          ON c.attempt_id = a.attempt_id
        WHERE a.sandbox_id = ?
        ORDER BY a.created_at, a.attempt_id
        LIMIT 8
        """,
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

  public boolean productVersionExists(String sandboxId, String productId, long version) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM eval_sandbox_product_fixture
            WHERE sandbox_id = ? AND product_id = ? AND publication_version = ?
            """,
            Integer.class,
            sandboxId,
            productId,
            version);
    return count != null && count == 1;
  }

  public boolean paymentCallbackVersionExists(
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String callbackEventId,
      long version) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM mock_payment_callback c
            JOIN mock_payment_attempt a
              ON a.attempt_id = c.attempt_id AND a.sandbox_id = c.sandbox_id
             AND a.callback_correlation_id = c.callback_correlation_id
            JOIN standard_order o
              ON o.order_id = a.order_id AND o.sandbox_id = a.sandbox_id
             AND o.user_subject = a.user_subject
             AND o.total_price_minor = a.amount_minor AND o.currency = a.currency
            JOIN inventory_ledger l
              ON l.business_event_key = CONCAT('mock-payment:', a.attempt_id)
             AND l.sandbox_id = a.sandbox_id AND l.movement_type = 'STANDARD_PAYMENT'
             AND l.order_id = a.order_id AND l.payment_amount_minor = a.amount_minor
             AND l.product_id = o.product_id AND l.reservation_id IS NULL
             AND l.activity_id IS NULL AND l.inventory_delta = 0
             AND l.activity_quota_delta = 0 AND l.payment_currency = a.currency
            WHERE c.sandbox_id = ? AND c.support_session_id = ? AND c.trace_id = ?
              AND c.operation_id = ? AND c.callback_event_id = ?
              AND c.requested_outcome = 'SUCCEEDED' AND c.result_state = 'APPLIED'
              AND c.intent_hash = SHA2(CONCAT_WS('\n', c.callback_event_id,
                c.callback_correlation_id, a.order_id, a.amount_minor, a.currency,
                c.requested_outcome, c.sandbox_id, c.support_session_id, c.trace_id,
                c.operation_id, c.callback_idempotency_key), 256)
              AND a.order_kind = 'STANDARD'
              AND a.state = 'SUCCEEDED' AND a.state_version = ?
              AND o.status = 'PAID' AND o.state_version = 2
            """,
            Integer.class,
            sandboxId,
            supportSessionId,
            traceId,
            operationId,
            callbackEventId,
            version);
    return count != null && count == 1;
  }

  public boolean paymentAuditReferencesConsistent(String sandboxId) {
    Integer invalid =
        jdbc.queryForObject(
            """
            WITH successful_payment AS (
              SELECT DISTINCT c.callback_event_id, c.sandbox_id, c.support_session_id,
                     c.trace_id, c.operation_id, a.state_version
              FROM mock_payment_callback c
              JOIN mock_payment_attempt a
                ON a.attempt_id = c.attempt_id AND a.sandbox_id = c.sandbox_id
               AND a.callback_correlation_id = c.callback_correlation_id
              JOIN standard_order o
                ON o.order_id = a.order_id AND o.sandbox_id = a.sandbox_id
               AND o.user_subject = a.user_subject
               AND o.total_price_minor = a.amount_minor AND o.currency = a.currency
              JOIN inventory_ledger l
                ON l.business_event_key = CONCAT('mock-payment:', a.attempt_id)
               AND l.sandbox_id = a.sandbox_id AND l.movement_type = 'STANDARD_PAYMENT'
               AND l.order_id = a.order_id AND l.product_id = o.product_id
               AND l.reservation_id IS NULL AND l.activity_id IS NULL
               AND l.inventory_delta = 0 AND l.activity_quota_delta = 0
               AND l.payment_amount_minor = a.amount_minor AND l.payment_currency = a.currency
              WHERE a.sandbox_id = ?
                AND c.requested_outcome = 'SUCCEEDED' AND c.result_state = 'APPLIED'
                AND c.intent_hash = SHA2(CONCAT_WS('\n', c.callback_event_id,
                  c.callback_correlation_id, a.order_id, a.amount_minor, a.currency,
                  c.requested_outcome, c.sandbox_id, c.support_session_id, c.trace_id,
                  c.operation_id, c.callback_idempotency_key), 256)
                AND a.order_kind = 'STANDARD' AND a.state = 'SUCCEEDED'
                AND o.status = 'PAID' AND o.state_version = 2
            )
            SELECT COUNT(*)
            FROM (
              SELECT p.callback_event_id
              FROM successful_payment p
              WHERE (
                SELECT COUNT(*)
                FROM eval_commerce_audit_reference r
                WHERE r.audit_reference_id = SHA2(CONCAT_WS('\n', p.sandbox_id,
                    p.support_session_id, p.trace_id, p.operation_id,
                    p.callback_event_id, p.state_version), 256)
                  AND r.sandbox_id = p.sandbox_id
                  AND r.support_session_id = p.support_session_id
                  AND r.trace_id = p.trace_id AND r.operation_id = p.operation_id
                  AND r.entity_type = 'PAYMENT_CALLBACK'
                  AND r.entity_id = p.callback_event_id
                  AND r.entity_version = p.state_version AND r.outcome = 'OBSERVED'
              ) <> 1
              UNION ALL
              SELECT r.audit_reference_id
              FROM eval_commerce_audit_reference r
              WHERE r.entity_type = 'PAYMENT_CALLBACK'
                AND (r.sandbox_id = ? OR EXISTS (
                  SELECT 1 FROM successful_payment p
                  WHERE p.callback_event_id = r.entity_id
                ))
                AND NOT EXISTS (
                  SELECT 1
                  FROM successful_payment p
                  WHERE r.audit_reference_id = SHA2(CONCAT_WS('\n', p.sandbox_id,
                      p.support_session_id, p.trace_id, p.operation_id,
                      p.callback_event_id, p.state_version), 256)
                    AND r.sandbox_id = p.sandbox_id
                    AND r.support_session_id = p.support_session_id
                    AND r.trace_id = p.trace_id AND r.operation_id = p.operation_id
                    AND r.entity_id = p.callback_event_id
                    AND r.entity_version = p.state_version AND r.outcome = 'OBSERVED'
                )
            ) inconsistent_reference
            """,
            Integer.class,
            sandboxId,
            sandboxId);
    return invalid != null && invalid == 0;
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

  private static Instant instant(ResultSet result, String column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

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
