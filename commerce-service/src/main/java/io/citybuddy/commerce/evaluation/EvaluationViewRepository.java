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
        WHERE sandbox_id = ? ORDER BY effect_type LIMIT 8
        """,
        (result, row) ->
            new EffectView(
                result.getString("effect_type"),
                result.getString("outcome"),
                result.getTimestamp("created_at").toInstant()),
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
