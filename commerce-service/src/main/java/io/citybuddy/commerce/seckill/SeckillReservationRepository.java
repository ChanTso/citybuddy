package io.citybuddy.commerce.seckill;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class SeckillReservationRepository {
  private static final String COLUMNS =
      "reservation_id, user_subject, activity_id, idempotency_key, intent_hash, quantity, "
          + "activity_projection_version, state, decision_code, projection_version";

  private final JdbcTemplate jdbc;

  public SeckillReservationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void reservePending(SeckillReservation reservation) {
    jdbc.update(
        """
        INSERT INTO seckill_reservation
          (reservation_id, user_subject, activity_id, idempotency_key, intent_hash, quantity,
           activity_projection_version, state, projection_version)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 1)
        """,
        reservation.reservationId(),
        reservation.userSubject(),
        reservation.activityId(),
        reservation.idempotencyKey(),
        reservation.intentHash(),
        reservation.quantity(),
        reservation.activityProjectionVersion());
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

  public long admittedQuantity(String activityId) {
    Long quantity =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(quantity), 0) FROM seckill_reservation "
                + "WHERE activity_id = ? AND state = 'ADMITTED'",
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
        2);
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
        result.getLong("projection_version"));
  }
}
