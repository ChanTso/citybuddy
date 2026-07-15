package io.citybuddy.commerce.seckill;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class SeckillActivityRepository {
  private static final String ACTIVITY_COLUMNS =
      "activity_id, product_id, starts_at, ends_at, state, allocated_quota, projection_version";

  private final JdbcTemplate jdbc;

  public SeckillActivityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Long> lockProductInventory(String productId) {
    return jdbc
        .query(
            "SELECT stock_quantity FROM product WHERE product_id = ? FOR UPDATE",
            (result, row) -> result.getLong("stock_quantity"),
            productId)
        .stream()
        .findFirst();
  }

  public Optional<SeckillActivity> findForUpdate(String activityId) {
    return jdbc
        .query(
            "SELECT "
                + ACTIVITY_COLUMNS
                + " FROM seckill_activity WHERE activity_id = ? FOR UPDATE",
            SeckillActivityRepository::mapActivity,
            activityId)
        .stream()
        .findFirst();
  }

  public Optional<SeckillActivity> find(String activityId) {
    return jdbc
        .query(
            "SELECT " + ACTIVITY_COLUMNS + " FROM seckill_activity WHERE activity_id = ?",
            SeckillActivityRepository::mapActivity,
            activityId)
        .stream()
        .findFirst();
  }

  public void insert(SeckillActivity activity) {
    jdbc.update(
        """
        INSERT INTO seckill_activity
          (activity_id, product_id, starts_at, ends_at, state, allocated_quota,
           projection_version)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        activity.activityId(),
        activity.productId(),
        Timestamp.from(activity.startsAt()),
        Timestamp.from(activity.endsAt()),
        activity.state().name(),
        activity.allocatedQuota(),
        activity.projectionVersion());
  }

  public SeckillActivity updateAllocation(SeckillActivity current, long allocatedQuota) {
    long nextVersion = Math.addExact(current.projectionVersion(), 1);
    int changed =
        jdbc.update(
            """
            UPDATE seckill_activity
            SET allocated_quota = ?, projection_version = ?
            WHERE activity_id = ? AND projection_version = ? AND state <> 'CLOSED'
            """,
            allocatedQuota,
            nextVersion,
            current.activityId(),
            current.projectionVersion());
    if (changed != 1) {
      throw new IllegalStateException("Seckill activity changed during its locked update");
    }
    return new SeckillActivity(
        current.activityId(),
        current.productId(),
        current.startsAt(),
        current.endsAt(),
        current.state(),
        allocatedQuota,
        nextVersion);
  }

  private static SeckillActivity mapActivity(ResultSet result, int row) throws SQLException {
    return new SeckillActivity(
        result.getString("activity_id"),
        result.getString("product_id"),
        result.getTimestamp("starts_at").toInstant(),
        result.getTimestamp("ends_at").toInstant(),
        SeckillActivityState.valueOf(result.getString("state")),
        result.getLong("allocated_quota"),
        result.getLong("projection_version"));
  }
}
