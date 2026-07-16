package io.citybuddy.commerce.payment;

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
    List<OrderTruth> standard =
        jdbc.query(
            """
            SELECT order_id, user_subject, product_id, total_price_minor, currency,
                   status, state_version
            FROM standard_order
            WHERE order_id = ?
            FOR UPDATE
            """,
            (result, row) ->
                new OrderTruth(
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
    List<OrderTruth> seckill =
        jdbc.query(
            """
            SELECT order_id, user_subject, product_id, reservation_id, activity_id,
                   total_price_minor, currency, status, state_version
            FROM seckill_order
            WHERE order_id = ?
            FOR UPDATE
            """,
            (result, row) ->
                new OrderTruth(
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
      throw new IllegalStateException("Payment order identifier is ambiguous");
    }
    return standard.isEmpty() ? seckill.stream().findFirst() : standard.stream().findFirst();
  }

  public Optional<AttemptRecord> findAttemptByRequestForUpdate(String user, String key) {
    return queryAttempt(
        "SELECT "
            + attemptColumns()
            + " FROM mock_payment_attempt WHERE user_subject = ? "
            + "AND request_idempotency_key = ? FOR UPDATE",
        user,
        key);
  }

  public Optional<AttemptRecord> findAttemptByOrderForUpdate(String orderKind, String orderId) {
    return queryAttempt(
        "SELECT "
            + attemptColumns()
            + " FROM mock_payment_attempt WHERE order_kind = ? AND order_id = ? FOR UPDATE",
        orderKind,
        orderId);
  }

  public Optional<AttemptRecord> findAttemptByCorrelationForUpdate(String correlationId) {
    return queryAttempt(
        "SELECT "
            + attemptColumns()
            + " FROM mock_payment_attempt WHERE callback_correlation_id = ? FOR UPDATE",
        correlationId);
  }

  public Optional<AttemptRecord> findAttemptByIdForUpdate(String attemptId) {
    return queryAttempt(
        "SELECT " + attemptColumns() + " FROM mock_payment_attempt WHERE attempt_id = ? FOR UPDATE",
        attemptId);
  }

  public void insertAttempt(AttemptRecord attempt) {
    jdbc.update(
        """
        INSERT INTO mock_payment_attempt
          (attempt_id, callback_correlation_id, user_subject, order_id, order_kind,
           request_idempotency_key, intent_hash, amount_minor, currency)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        attempt.attemptId(),
        attempt.callbackCorrelationId(),
        attempt.userSubject(),
        attempt.orderId(),
        attempt.orderKind(),
        attempt.requestIdempotencyKey(),
        attempt.intentHash(),
        attempt.amountMinor(),
        attempt.currency());
  }

  public Optional<CallbackRecord> findCallbackByKey(String idempotencyKey) {
    return queryCallback(
        "SELECT "
            + callbackColumns()
            + " FROM mock_payment_callback WHERE callback_idempotency_key = ?",
        idempotencyKey);
  }

  public Optional<CallbackRecord> findCallbackByEvent(String eventId) {
    return queryCallback(
        "SELECT " + callbackColumns() + " FROM mock_payment_callback WHERE callback_event_id = ?",
        eventId);
  }

  public Optional<CallbackRecord> findCallbackByAttempt(String attemptId) {
    return queryCallback(
        "SELECT " + callbackColumns() + " FROM mock_payment_callback WHERE attempt_id = ?",
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
           activity_id, product_id, inventory_delta, activity_quota_delta,
           payment_amount_minor, payment_currency)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)
        """,
        UUID.randomUUID().toString(),
        "mock-payment:" + attempt.attemptId(),
        movementType,
        order.orderId(),
        order.reservationId(),
        order.activityId(),
        order.productId(),
        attempt.amountMinor(),
        attempt.currency());
  }

  public void insertCallback(CallbackRecord callback) {
    jdbc.update(
        """
        INSERT INTO mock_payment_callback
          (callback_event_id, callback_idempotency_key, attempt_id,
           callback_correlation_id, intent_hash, requested_outcome, result_state)
        VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', 'APPLIED')
        """,
        callback.callbackEventId(),
        callback.callbackIdempotencyKey(),
        callback.attemptId(),
        callback.callbackCorrelationId(),
        callback.intentHash());
  }

  public boolean hasPaymentMovement(String attemptId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE business_event_key = ? "
                + "AND movement_type IN ('STANDARD_PAYMENT', 'SECKILL_PAYMENT')",
            Integer.class,
            "mock-payment:" + attemptId);
    return count != null && count == 1;
  }

  private Optional<AttemptRecord> queryAttempt(String sql, Object... arguments) {
    List<AttemptRecord> rows = jdbc.query(sql, MockPaymentRepository::mapAttempt, arguments);
    if (rows.size() > 1) {
      throw new IllegalStateException("Payment attempt uniqueness is corrupted");
    }
    return rows.stream().findFirst();
  }

  private Optional<CallbackRecord> queryCallback(String sql, Object... arguments) {
    List<CallbackRecord> rows = jdbc.query(sql, MockPaymentRepository::mapCallback, arguments);
    if (rows.size() > 1) {
      throw new IllegalStateException("Payment callback uniqueness is corrupted");
    }
    return rows.stream().findFirst();
  }

  private static AttemptRecord mapAttempt(java.sql.ResultSet result, int row)
      throws java.sql.SQLException {
    return new AttemptRecord(
        result.getString("attempt_id"),
        result.getString("callback_correlation_id"),
        result.getString("user_subject"),
        result.getString("order_id"),
        result.getString("order_kind"),
        result.getString("request_idempotency_key"),
        result.getString("intent_hash"),
        result.getLong("amount_minor"),
        result.getLong("refunded_amount_minor"),
        result.getString("currency"),
        result.getString("state"),
        result.getLong("state_version"));
  }

  private static CallbackRecord mapCallback(java.sql.ResultSet result, int row)
      throws java.sql.SQLException {
    return new CallbackRecord(
        result.getString("callback_event_id"),
        result.getString("callback_idempotency_key"),
        result.getString("attempt_id"),
        result.getString("callback_correlation_id"),
        result.getString("intent_hash"));
  }

  private static String attemptColumns() {
    return "attempt_id, callback_correlation_id, user_subject, order_id, order_kind, "
        + "request_idempotency_key, intent_hash, amount_minor, refunded_amount_minor, currency, "
        + "state, state_version";
  }

  private static String callbackColumns() {
    return "callback_event_id, callback_idempotency_key, attempt_id, "
        + "callback_correlation_id, intent_hash";
  }

  public record OrderTruth(
      String orderKind,
      String orderId,
      String userSubject,
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
      String requestIdempotencyKey,
      String intentHash,
      long amountMinor,
      long refundedAmountMinor,
      String currency,
      String state,
      long stateVersion) {
    static AttemptRecord pending(
        String attemptId,
        String callbackCorrelationId,
        String userSubject,
        String orderId,
        String orderKind,
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
          requestIdempotencyKey,
          intentHash,
          amountMinor,
          0,
          currency,
          "PENDING",
          1);
    }
  }

  public record CallbackRecord(
      String callbackEventId,
      String callbackIdempotencyKey,
      String attemptId,
      String callbackCorrelationId,
      String intentHash) {}
}
