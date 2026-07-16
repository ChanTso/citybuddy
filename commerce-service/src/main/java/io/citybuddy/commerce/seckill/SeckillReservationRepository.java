package io.citybuddy.commerce.seckill;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class SeckillReservationRepository {
  private static final String COLUMNS =
      "reservation_id, user_subject, activity_id, idempotency_key, intent_hash, quantity, "
          + "activity_projection_version, state, decision_code, projection_version, order_id, "
          + "transaction_resolution_due_at";

  private final JdbcTemplate jdbc;

  public SeckillReservationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void reservePending(SeckillReservation reservation, Duration resolutionWindow) {
    long resolutionWindowMicros;
    try {
      resolutionWindowMicros = Math.multiplyExact(resolutionWindow.toMillis(), 1_000L);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("Transaction resolution window is too large", exception);
    }
    if (resolutionWindowMicros < 1) {
      throw new IllegalArgumentException("Transaction resolution window must be positive");
    }
    jdbc.update(
        """
        INSERT INTO seckill_reservation
          (reservation_id, user_subject, activity_id, idempotency_key, intent_hash, quantity,
           activity_projection_version, state, projection_version, transaction_resolution_due_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 1,
                TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)))
        """,
        reservation.reservationId(),
        reservation.userSubject(),
        reservation.activityId(),
        reservation.idempotencyKey(),
        reservation.intentHash(),
        reservation.quantity(),
        reservation.activityProjectionVersion(),
        resolutionWindowMicros);
  }

  public Optional<SeckillReservation> findByIdempotencyForUpdate(
      String userSubject, String activityId, String idempotencyKey) {
    return queryOne(
        "SELECT "
            + COLUMNS
            + " FROM seckill_reservation "
            + "WHERE user_subject = ? AND activity_id = ? AND idempotency_key = ? FOR UPDATE",
        userSubject,
        activityId,
        idempotencyKey);
  }

  public Optional<SeckillReservation> findForUpdate(String reservationId) {
    return queryOne(
        "SELECT " + COLUMNS + " FROM seckill_reservation WHERE reservation_id = ? FOR UPDATE",
        reservationId);
  }

  public Optional<SeckillReservation> find(String reservationId) {
    return queryOne(
        "SELECT " + COLUMNS + " FROM seckill_reservation WHERE reservation_id = ?", reservationId);
  }

  public Optional<SeckillReservation> findOwned(String userSubject, String reservationId) {
    return queryOne(
        "SELECT "
            + COLUMNS
            + " FROM seckill_reservation WHERE user_subject = ? AND reservation_id = ?",
        userSubject,
        reservationId);
  }

  public List<SeckillReservation> findAllForActivityForUpdate(String activityId) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM seckill_reservation WHERE activity_id = ? "
            + "ORDER BY reservation_id FOR UPDATE",
        SeckillReservationRepository::mapReservation,
        activityId);
  }

  public boolean hasPendingForActivity(String activityId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id = ? AND state = 'PENDING'",
            Integer.class,
            activityId);
    return count != null && count > 0;
  }

  public List<SeckillReservation> findDuePending(int limit) {
    if (limit < 1 || limit > 1_000) {
      throw new IllegalArgumentException("Due reservation batch size is invalid");
    }
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM seckill_reservation "
            + "WHERE state = 'PENDING' AND transaction_resolution_due_at <= CURRENT_TIMESTAMP(6) "
            + "ORDER BY transaction_resolution_due_at, reservation_id LIMIT ?",
        SeckillReservationRepository::mapReservation,
        limit);
  }

  public long admittedQuantity(String activityId) {
    Long quantity =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(quantity), 0) FROM seckill_reservation "
                + "WHERE activity_id = ? AND state IN ('ADMITTED', 'ORDERED')",
            Long.class,
            activityId);
    return quantity == null ? 0 : quantity;
  }

  public SeckillReservation applyDecision(
      SeckillReservation current, ReservationState state, ReservationDecisionCode decisionCode) {
    int changed =
        jdbc.update(
            """
            UPDATE seckill_reservation
            SET state = ?, decision_code = ?, projection_version = 2
            WHERE reservation_id = ? AND state = 'PENDING' AND projection_version = 1
            """,
            state.name(),
            decisionCode.name(),
            current.reservationId());
    if (changed != 1) {
      throw new IllegalStateException("Reservation changed during its locked decision update");
    }
    return new SeckillReservation(
        current.reservationId(),
        current.userSubject(),
        current.activityId(),
        current.idempotencyKey(),
        current.intentHash(),
        current.quantity(),
        current.activityProjectionVersion(),
        state,
        decisionCode,
        2,
        null,
        current.transactionResolutionDueAt());
  }

  public SeckillReservation markOrdered(SeckillReservation current, String orderId) {
    int changed =
        jdbc.update(
            """
            UPDATE seckill_reservation
            SET state = 'ORDERED', projection_version = 3, order_id = ?
            WHERE reservation_id = ? AND state = 'ADMITTED'
              AND projection_version = 2 AND order_id IS NULL
            """,
            orderId,
            current.reservationId());
    if (changed != 1) {
      throw new IllegalStateException("Reservation changed during its locked order transition");
    }
    return new SeckillReservation(
        current.reservationId(),
        current.userSubject(),
        current.activityId(),
        current.idempotencyKey(),
        current.intentHash(),
        current.quantity(),
        current.activityProjectionVersion(),
        ReservationState.ORDERED,
        current.decisionCode(),
        3,
        orderId,
        current.transactionResolutionDueAt());
  }

  private Optional<SeckillReservation> queryOne(String sql, Object... arguments) {
    return jdbc.query(sql, SeckillReservationRepository::mapReservation, arguments).stream()
        .findFirst();
  }

  private static SeckillReservation mapReservation(ResultSet result, int row) throws SQLException {
    String code = result.getString("decision_code");
    return new SeckillReservation(
        result.getString("reservation_id"),
        result.getString("user_subject"),
        result.getString("activity_id"),
        result.getString("idempotency_key"),
        result.getString("intent_hash"),
        result.getInt("quantity"),
        result.getLong("activity_projection_version"),
        ReservationState.valueOf(result.getString("state")),
        code == null ? null : ReservationDecisionCode.valueOf(code),
        result.getLong("projection_version"),
        result.getString("order_id"),
        result.getTimestamp("transaction_resolution_due_at").toInstant());
  }
}
