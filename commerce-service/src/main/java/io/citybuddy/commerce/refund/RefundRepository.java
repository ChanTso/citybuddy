package io.citybuddy.commerce.refund;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.payment.MockPaymentRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class RefundRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public RefundRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  public Optional<MockPaymentRepository.OrderTruth> findOrder(String orderId) {
    List<MockPaymentRepository.OrderTruth> standard =
        jdbc.query(
            """
            SELECT order_id, user_subject, product_id, total_price_minor, currency,
                   status, state_version
            FROM standard_order
            WHERE order_id = ?
            """,
            (result, row) ->
                new MockPaymentRepository.OrderTruth(
                    "STANDARD",
                    result.getString("order_id"),
                    result.getString("user_subject"),
                    result.getString("product_id"),
                    null,
                    null,
                    result.getLong("total_price_minor"),
                    result.getString("currency"),
                    result.getString("status"),
                    result.getLong("state_version")),
            orderId);
    List<MockPaymentRepository.OrderTruth> seckill =
        jdbc.query(
            """
            SELECT order_id, user_subject, product_id, reservation_id, activity_id,
                   total_price_minor, currency, status, state_version
            FROM seckill_order
            WHERE order_id = ?
            """,
            (result, row) ->
                new MockPaymentRepository.OrderTruth(
                    "SECKILL",
                    result.getString("order_id"),
                    result.getString("user_subject"),
                    result.getString("product_id"),
                    result.getString("reservation_id"),
                    result.getString("activity_id"),
                    result.getLong("total_price_minor"),
                    result.getString("currency"),
                    result.getString("status"),
                    result.getLong("state_version")),
            orderId);
    if (standard.size() + seckill.size() > 1) {
      throw new IllegalStateException("Refund order identifier is ambiguous");
    }
    return standard.isEmpty() ? seckill.stream().findFirst() : standard.stream().findFirst();
  }

  public Optional<RefundRecord> findByRequestForUpdate(String user, String orderId, String key) {
    return queryRefund(
        "SELECT "
            + refundColumns()
            + " FROM mock_refund WHERE user_subject = ? AND order_id = ? "
            + "AND request_idempotency_key = ? FOR UPDATE",
        user,
        orderId,
        key);
  }

  public Optional<RefundRecord> findByIdForUpdate(String refundId) {
    return queryRefund(
        "SELECT " + refundColumns() + " FROM mock_refund WHERE refund_id = ? FOR UPDATE", refundId);
  }

  public Optional<RefundRecord> findById(String refundId) {
    return queryRefund(
        "SELECT " + refundColumns() + " FROM mock_refund WHERE refund_id = ?", refundId);
  }

  public Optional<RefundRecord> findOwnedById(String user, String refundId) {
    return queryRefund(
        "SELECT " + refundColumns() + " FROM mock_refund WHERE user_subject = ? AND refund_id = ?",
        user,
        refundId);
  }

  public List<RefundRecord> findByAttemptForUpdate(String attemptId) {
    return jdbc.query(
        "SELECT "
            + refundColumns()
            + " FROM mock_refund WHERE payment_attempt_id = ? "
            + "ORDER BY created_at, refund_id FOR UPDATE",
        RefundRepository::mapRefund,
        attemptId);
  }

  public long reservedAmount(String attemptId) {
    List<Long> amounts =
        jdbc.query(
            """
            SELECT requested_amount_minor
            FROM mock_refund
            WHERE payment_attempt_id = ?
              AND state IN ('REQUESTED', 'PROCESSING', 'SUCCEEDED')
            ORDER BY created_at, refund_id
            FOR UPDATE
            """,
            (result, row) -> result.getLong("requested_amount_minor"),
            attemptId);
    long total = 0;
    for (long amount : amounts) {
      total = Math.addExact(total, amount);
    }
    return total;
  }

  public void insertRefund(RefundRecord refund) {
    jdbc.update(
        """
        INSERT INTO mock_refund
          (refund_id, user_subject, order_id, order_kind, payment_attempt_id,
           request_idempotency_key, intent_hash, eligible_amount_minor,
           requested_amount_minor, currency)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        refund.refundId(),
        refund.userSubject(),
        refund.orderId(),
        refund.orderKind(),
        refund.paymentAttemptId(),
        refund.requestIdempotencyKey(),
        refund.intentHash(),
        refund.eligibleAmountMinor(),
        refund.requestedAmountMinor(),
        refund.currency());
  }

  public void markProcessing(RefundRecord refund, Instant now) {
    int changed =
        jdbc.update(
            """
            UPDATE mock_refund
            SET state = 'PROCESSING', state_version = 2, processing_at = ?
            WHERE refund_id = ? AND state = 'REQUESTED' AND state_version = 1
            """,
            Timestamp.from(now),
            refund.refundId());
    requireOne(changed, "Refund changed during its processing transition");
  }

  public void markSucceeded(RefundRecord refund, Instant now) {
    int changed =
        jdbc.update(
            """
            UPDATE mock_refund
            SET state = 'SUCCEEDED', state_version = 3,
                refunded_amount_minor = requested_amount_minor, completed_at = ?
            WHERE refund_id = ? AND state = 'PROCESSING' AND state_version = 2
            """,
            Timestamp.from(now),
            refund.refundId());
    requireOne(changed, "Refund changed during its success transition");
  }

  public void markFailed(RefundRecord refund, String failureCode, Instant now) {
    int changed =
        jdbc.update(
            """
            UPDATE mock_refund
            SET state = 'FAILED', state_version = 3, failure_code = ?, completed_at = ?
            WHERE refund_id = ? AND state = 'PROCESSING' AND state_version = 2
            """,
            failureCode,
            Timestamp.from(now),
            refund.refundId());
    requireOne(changed, "Refund changed during its failure transition");
  }

  public void addRefundedAmount(MockPaymentRepository.AttemptRecord attempt, long amountMinor) {
    int changed =
        jdbc.update(
            """
            UPDATE mock_payment_attempt
            SET refunded_amount_minor = refunded_amount_minor + ?
            WHERE attempt_id = ? AND state = 'SUCCEEDED' AND state_version = 2
              AND refunded_amount_minor = ?
              AND refunded_amount_minor + ? <= amount_minor
            """,
            amountMinor,
            attempt.attemptId(),
            attempt.refundedAmountMinor(),
            amountMinor);
    requireOne(changed, "Refund would exceed authoritative paid amount");
  }

  public void convergeRefundedAmount(
      MockPaymentRepository.AttemptRecord attempt, long amountMinor) {
    int changed =
        jdbc.update(
            """
            UPDATE mock_payment_attempt
            SET refunded_amount_minor = ?
            WHERE attempt_id = ? AND state = 'SUCCEEDED' AND state_version = 2
              AND refunded_amount_minor = ? AND ? <= amount_minor
            """,
            amountMinor,
            attempt.attemptId(),
            attempt.refundedAmountMinor(),
            amountMinor);
    requireOne(changed, "Payment refund aggregate changed during reconciliation");
  }

  public void insertRefundMovement(RefundRecord refund, MockPaymentRepository.OrderTruth order) {
    jdbc.update(
        """
        INSERT INTO inventory_ledger
          (movement_id, business_event_key, movement_type, order_id, reservation_id,
           activity_id, product_id, inventory_delta, activity_quota_delta,
           payment_amount_minor, payment_currency)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)
        """,
        UUID.randomUUID().toString(),
        refundEventKey(refund.refundId()),
        refund.orderKind() + "_REFUND",
        refund.orderId(),
        order.reservationId(),
        order.activityId(),
        order.productId(),
        refund.requestedAmountMinor(),
        refund.currency());
  }

  public Optional<MovementRecord> findMovement(String businessEventKey) {
    List<MovementRecord> rows =
        jdbc.query(
            """
            SELECT business_event_key, movement_type, order_id, reservation_id, activity_id,
                   product_id, inventory_delta, activity_quota_delta,
                   payment_amount_minor, payment_currency
            FROM inventory_ledger
            WHERE business_event_key = ?
            FOR SHARE
            """,
            (result, row) ->
                new MovementRecord(
                    result.getString("business_event_key"),
                    result.getString("movement_type"),
                    result.getString("order_id"),
                    result.getString("reservation_id"),
                    result.getString("activity_id"),
                    result.getString("product_id"),
                    result.getLong("inventory_delta"),
                    result.getLong("activity_quota_delta"),
                    result.getLong("payment_amount_minor"),
                    result.getString("payment_currency")),
            businessEventKey);
    if (rows.size() > 1) {
      throw new IllegalStateException("Ledger business event uniqueness is corrupted");
    }
    return rows.stream().findFirst();
  }

  public List<MovementRecord> findRefundMovements(String orderId) {
    return jdbc.query(
        """
        SELECT business_event_key, movement_type, order_id, reservation_id, activity_id,
               product_id, inventory_delta, activity_quota_delta,
               payment_amount_minor, payment_currency
        FROM inventory_ledger
        WHERE order_id = ? AND movement_type IN ('STANDARD_REFUND', 'SECKILL_REFUND')
        ORDER BY business_event_key
        FOR SHARE
        """,
        (result, row) ->
            new MovementRecord(
                result.getString("business_event_key"),
                result.getString("movement_type"),
                result.getString("order_id"),
                result.getString("reservation_id"),
                result.getString("activity_id"),
                result.getString("product_id"),
                result.getLong("inventory_delta"),
                result.getLong("activity_quota_delta"),
                result.getLong("payment_amount_minor"),
                result.getString("payment_currency")),
        orderId);
  }

  public boolean hasMovement(String orderId, String movementType) {
    List<String> rows =
        jdbc.query(
            """
            SELECT business_event_key
            FROM inventory_ledger
            WHERE order_id = ? AND movement_type = ?
            LIMIT 1
            FOR SHARE
            """,
            (result, row) -> result.getString("business_event_key"),
            orderId,
            movementType);
    return !rows.isEmpty();
  }

  public void insertOutbox(RefundRecord refund, String eventType, long version) {
    String eventId = UUID.randomUUID().toString();
    Map<String, Object> event =
        Map.of(
            "eventId", eventId,
            "refundId", refund.refundId(),
            "orderId", refund.orderId(),
            "paymentAttemptId", refund.paymentAttemptId(),
            "amountMinor", refund.requestedAmountMinor(),
            "currency", refund.currency(),
            "stateVersion", version);
    jdbc.update(
        """
        INSERT INTO commerce_outbox
          (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload)
        VALUES (?, 'REFUND', ?, ?, ?, CAST(? AS JSON))
        """,
        eventId,
        refund.refundId(),
        version,
        eventType,
        json(event));
  }

  private Optional<RefundRecord> queryRefund(String sql, Object... arguments) {
    List<RefundRecord> rows = jdbc.query(sql, RefundRepository::mapRefund, arguments);
    if (rows.size() > 1) {
      throw new IllegalStateException("Refund uniqueness is corrupted");
    }
    return rows.stream().findFirst();
  }

  private String json(Map<String, Object> event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Refund event serialization failed", exception);
    }
  }

  private static RefundRecord mapRefund(java.sql.ResultSet result, int row)
      throws java.sql.SQLException {
    return new RefundRecord(
        result.getString("refund_id"),
        result.getString("user_subject"),
        result.getString("order_id"),
        result.getString("order_kind"),
        result.getString("payment_attempt_id"),
        result.getString("request_idempotency_key"),
        result.getString("intent_hash"),
        result.getLong("eligible_amount_minor"),
        result.getLong("requested_amount_minor"),
        result.getLong("refunded_amount_minor"),
        result.getString("currency"),
        result.getString("state"),
        result.getLong("state_version"),
        result.getString("failure_code"));
  }

  private static String refundColumns() {
    return "refund_id, user_subject, order_id, order_kind, payment_attempt_id, "
        + "request_idempotency_key, intent_hash, eligible_amount_minor, requested_amount_minor, "
        + "refunded_amount_minor, currency, state, state_version, failure_code";
  }

  private static void requireOne(int changed, String message) {
    if (changed != 1) {
      throw new IllegalStateException(message);
    }
  }

  public static String refundEventKey(String refundId) {
    return "mock-refund:" + refundId;
  }

  public record RefundRecord(
      String refundId,
      String userSubject,
      String orderId,
      String orderKind,
      String paymentAttemptId,
      String requestIdempotencyKey,
      String intentHash,
      long eligibleAmountMinor,
      long requestedAmountMinor,
      long refundedAmountMinor,
      String currency,
      String state,
      long stateVersion,
      String failureCode) {
    static RefundRecord requested(
        String refundId,
        String userSubject,
        String orderId,
        String orderKind,
        String paymentAttemptId,
        String requestIdempotencyKey,
        String intentHash,
        long eligibleAmountMinor,
        long requestedAmountMinor,
        String currency) {
      return new RefundRecord(
          refundId,
          userSubject,
          orderId,
          orderKind,
          paymentAttemptId,
          requestIdempotencyKey,
          intentHash,
          eligibleAmountMinor,
          requestedAmountMinor,
          0,
          currency,
          "REQUESTED",
          1,
          null);
    }
  }

  public record MovementRecord(
      String businessEventKey,
      String movementType,
      String orderId,
      String reservationId,
      String activityId,
      String productId,
      long inventoryDelta,
      long activityQuotaDelta,
      long amountMinor,
      String currency) {}
}
