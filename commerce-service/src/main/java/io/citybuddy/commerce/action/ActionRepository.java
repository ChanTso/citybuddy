package io.citybuddy.commerce.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

public class ActionRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public ActionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  public <T> T withLockWaitTimeout(int seconds, Supplier<T> work) {
    Long previous = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Long.class);
    if (previous == null) {
      throw new IllegalStateException("MySQL lock wait timeout is unavailable");
    }
    try {
      jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + seconds);
      return work.get();
    } finally {
      jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + previous);
    }
  }

  public Optional<PendingActionRecord> findPendingByTurnForUpdate(
      String user, String session, String turnId) {
    return queryPending(
        "SELECT "
            + pendingColumns()
            + " FROM pending_action WHERE user_subject = ? AND support_session_id = ? "
            + "AND turn_id = ? FOR UPDATE",
        user,
        session,
        turnId);
  }

  public Optional<PendingActionRecord> findPendingByIdForUpdate(String pendingActionId) {
    return queryPending(
        "SELECT "
            + pendingColumns()
            + " FROM pending_action WHERE pending_action_id = ? FOR UPDATE",
        pendingActionId);
  }

  public Optional<PendingActionRecord> findPendingById(String pendingActionId) {
    return queryPending(
        "SELECT " + pendingColumns() + " FROM pending_action WHERE pending_action_id = ?",
        pendingActionId);
  }

  public void insertPending(PendingActionRecord action) {
    jdbc.update(
        """
        INSERT INTO pending_action
          (pending_action_id, action_idempotency_key, action_type, argument_hash,
           user_subject, support_session_id, trace_id, turn_id, required_scope, sandbox_id,
           order_id, order_kind, payment_attempt_id, target_order_version, amount_minor,
           currency, expires_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        action.pendingActionId(),
        action.actionIdempotencyKey(),
        action.actionType(),
        action.argumentHash(),
        action.userSubject(),
        action.supportSessionId(),
        action.traceId(),
        action.turnId(),
        action.requiredScope(),
        action.sandboxId(),
        action.orderId(),
        action.orderKind(),
        action.paymentAttemptId(),
        action.targetOrderVersion(),
        action.amountMinor(),
        action.currency(),
        Timestamp.from(action.expiresAt()),
        Timestamp.from(action.createdAt()));
  }

  public void consume(PendingActionRecord action, Instant committedAt) {
    int changed =
        jdbc.update(
            """
            UPDATE pending_action
            SET state = 'CONSUMED', state_version = 2, consumed_at = ?
            WHERE pending_action_id = ? AND state = 'PREPARED' AND state_version = 1
              AND consumed_at IS NULL
            """,
            Timestamp.from(committedAt),
            action.pendingActionId());
    requireOne(changed, "PendingAction changed during confirmation");
  }

  public Optional<ActionReceiptRecord> findReceiptByPending(String pendingActionId) {
    return queryReceipt(
        "SELECT " + receiptColumns() + " FROM action_receipt WHERE pending_action_id = ?",
        pendingActionId);
  }

  public void insertReceipt(ActionReceiptRecord receipt) {
    jdbc.update(
        """
        INSERT INTO action_receipt
          (receipt_id, receipt_idempotency_key, pending_action_id, action_type,
           argument_hash, result_hash, user_subject, support_session_id, trace_id, turn_id,
           sandbox_id, order_id, payment_attempt_id, refund_id, resulting_resource_version,
           result_state, amount_minor, currency, outbox_event_id, outbox_created_at, committed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        receipt.receiptId(),
        receipt.receiptIdempotencyKey(),
        receipt.pendingActionId(),
        receipt.actionType(),
        receipt.argumentHash(),
        receipt.resultHash(),
        receipt.userSubject(),
        receipt.supportSessionId(),
        receipt.traceId(),
        receipt.turnId(),
        receipt.sandboxId(),
        receipt.orderId(),
        receipt.paymentAttemptId(),
        receipt.refundId(),
        receipt.resultingResourceVersion(),
        receipt.resultState(),
        receipt.amountMinor(),
        receipt.currency(),
        receipt.outboxEventId(),
        Timestamp.from(receipt.outboxCreatedAt()),
        Timestamp.from(receipt.committedAt()));
  }

  public Optional<RefundOutboxRecord> findRefundRequestedOutbox(String eventId) {
    List<RefundOutboxRecord> rows =
        jdbc.query(
            """
            SELECT event_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                   CAST(payload AS CHAR) AS payload, publication_state, publish_attempts,
                   created_at, published_at
            FROM commerce_outbox
            WHERE event_id = ?
            FOR SHARE
            """,
            (result, row) ->
                new RefundOutboxRecord(
                    result.getString("event_id"),
                    result.getString("aggregate_type"),
                    result.getString("aggregate_id"),
                    result.getLong("aggregate_version"),
                    result.getString("event_type"),
                    parseJson(result.getString("payload")),
                    result.getString("publication_state"),
                    result.getLong("publish_attempts"),
                    instant(result.getTimestamp("created_at")),
                    instant(result.getTimestamp("published_at"))),
            eventId);
    if (rows.size() > 1) {
      throw new ActionIntegrityException("Refund request Outbox uniqueness is corrupted");
    }
    return rows.stream().findFirst();
  }

  private Optional<PendingActionRecord> queryPending(String sql, Object... arguments) {
    List<PendingActionRecord> rows = jdbc.query(sql, ActionRepository::mapPending, arguments);
    if (rows.size() > 1) {
      throw new ActionIntegrityException("PendingAction uniqueness is corrupted");
    }
    return rows.stream().findFirst();
  }

  private Optional<ActionReceiptRecord> queryReceipt(String sql, Object... arguments) {
    List<ActionReceiptRecord> rows = jdbc.query(sql, ActionRepository::mapReceipt, arguments);
    if (rows.size() > 1) {
      throw new ActionIntegrityException("ActionReceipt uniqueness is corrupted");
    }
    return rows.stream().findFirst();
  }

  private JsonNode parseJson(String value) {
    try {
      return objectMapper.readTree(value);
    } catch (Exception exception) {
      throw new ActionIntegrityException("Refund request Outbox payload is malformed", exception);
    }
  }

  private static PendingActionRecord mapPending(java.sql.ResultSet result, int row)
      throws java.sql.SQLException {
    return new PendingActionRecord(
        result.getString("pending_action_id"),
        result.getString("action_idempotency_key"),
        result.getString("action_type"),
        result.getString("argument_hash"),
        result.getString("user_subject"),
        result.getString("support_session_id"),
        result.getString("trace_id"),
        result.getString("turn_id"),
        result.getString("required_scope"),
        result.getString("sandbox_id"),
        result.getString("order_id"),
        result.getString("order_kind"),
        result.getString("payment_attempt_id"),
        result.getLong("target_order_version"),
        result.getLong("amount_minor"),
        result.getString("currency"),
        result.getString("state"),
        result.getLong("state_version"),
        instant(result.getTimestamp("expires_at")),
        instant(result.getTimestamp("consumed_at")),
        instant(result.getTimestamp("created_at")));
  }

  private static ActionReceiptRecord mapReceipt(java.sql.ResultSet result, int row)
      throws java.sql.SQLException {
    return new ActionReceiptRecord(
        result.getString("receipt_id"),
        result.getString("receipt_idempotency_key"),
        result.getString("pending_action_id"),
        result.getString("action_type"),
        result.getString("argument_hash"),
        result.getString("result_hash"),
        result.getString("user_subject"),
        result.getString("support_session_id"),
        result.getString("trace_id"),
        result.getString("turn_id"),
        result.getString("sandbox_id"),
        result.getString("order_id"),
        result.getString("payment_attempt_id"),
        result.getString("refund_id"),
        result.getLong("resulting_resource_version"),
        result.getString("result_state"),
        result.getLong("amount_minor"),
        result.getString("currency"),
        result.getString("outbox_event_id"),
        instant(result.getTimestamp("outbox_created_at")),
        instant(result.getTimestamp("committed_at")));
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static String pendingColumns() {
    return "pending_action_id, action_idempotency_key, action_type, argument_hash, user_subject, "
        + "support_session_id, trace_id, turn_id, required_scope, sandbox_id, order_id, "
        + "order_kind, payment_attempt_id, target_order_version, amount_minor, currency, state, "
        + "state_version, expires_at, consumed_at, created_at";
  }

  private static String receiptColumns() {
    return "receipt_id, receipt_idempotency_key, pending_action_id, action_type, argument_hash, "
        + "result_hash, user_subject, support_session_id, trace_id, turn_id, sandbox_id, order_id, "
        + "payment_attempt_id, refund_id, resulting_resource_version, result_state, amount_minor, "
        + "currency, outbox_event_id, outbox_created_at, committed_at";
  }

  private static void requireOne(int changed, String message) {
    if (changed != 1) {
      throw new ActionIntegrityException(message);
    }
  }

  public record PendingActionRecord(
      String pendingActionId,
      String actionIdempotencyKey,
      String actionType,
      String argumentHash,
      String userSubject,
      String supportSessionId,
      String traceId,
      String turnId,
      String requiredScope,
      String sandboxId,
      String orderId,
      String orderKind,
      String paymentAttemptId,
      long targetOrderVersion,
      long amountMinor,
      String currency,
      String state,
      long stateVersion,
      Instant expiresAt,
      Instant consumedAt,
      Instant createdAt) {}

  public record ActionReceiptRecord(
      String receiptId,
      String receiptIdempotencyKey,
      String pendingActionId,
      String actionType,
      String argumentHash,
      String resultHash,
      String userSubject,
      String supportSessionId,
      String traceId,
      String turnId,
      String sandboxId,
      String orderId,
      String paymentAttemptId,
      String refundId,
      long resultingResourceVersion,
      String resultState,
      long amountMinor,
      String currency,
      String outboxEventId,
      Instant outboxCreatedAt,
      Instant committedAt) {}

  public record RefundOutboxRecord(
      String eventId,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      String eventType,
      JsonNode payload,
      String publicationState,
      long publishAttempts,
      Instant createdAt,
      Instant publishedAt) {}

  public static final class ActionIntegrityException extends RuntimeException {
    ActionIntegrityException(String message) {
      super(message);
    }

    ActionIntegrityException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
