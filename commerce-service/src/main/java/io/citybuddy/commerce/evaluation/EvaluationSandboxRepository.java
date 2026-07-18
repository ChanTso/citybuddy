package io.citybuddy.commerce.evaluation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class EvaluationSandboxRepository {
  private static final String COLUMNS =
      "sandbox_id, case_correlation, reset_idempotency_key, fixture_digest, fixture_count, "
          + "test_user_label, requested_ttl_seconds, auth_provision_idempotency_key, "
          + "auth_revoke_idempotency_key, opaque_handle, lifecycle_state, "
          + "auth_invalidation_state, death_reason, completion_idempotency_key, "
          + "cleanup_attempts, cleanup_due_at, provisioning_due_at, "
          + "auth_expiry_upper_bound, expires_at, activated_at, dead_at, closed_at, version";

  private final JdbcTemplate jdbc;

  public EvaluationSandboxRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public Sandbox registerOrLoad(NewSandbox request) {
    jdbc.update(
        """
        INSERT IGNORE INTO eval_sandbox
          (sandbox_id, case_correlation, reset_idempotency_key, fixture_digest, fixture_count,
           test_user_label, requested_ttl_seconds, auth_provision_idempotency_key,
           auth_revoke_idempotency_key, lifecycle_state, auth_invalidation_state,
           cleanup_due_at, provisioning_due_at, auth_expiry_upper_bound)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROVISIONING', 'UNPROVISIONED', ?, ?, ?)
        """,
        request.sandboxId(),
        request.caseCorrelation(),
        request.resetIdempotencyKey(),
        request.fixtureDigest(),
        request.fixtureCount(),
        request.testUserLabel(),
        request.ttlSeconds(),
        request.provisionIdempotencyKey(),
        request.revokeIdempotencyKey(),
        Timestamp.from(request.provisioningDueAt()),
        Timestamp.from(request.provisioningDueAt()),
        Timestamp.from(request.authExpiryUpperBound()));
    List<Sandbox> matches =
        jdbc.query(
            "SELECT "
                + COLUMNS
                + " FROM eval_sandbox WHERE sandbox_id = ? OR case_correlation = ? "
                + "OR reset_idempotency_key = ? FOR SHARE",
            EvaluationSandboxRepository::mapSandbox,
            request.sandboxId(),
            request.caseCorrelation(),
            request.resetIdempotencyKey());
    if (matches.size() != 1 || !sameReset(matches.getFirst(), request)) {
      throw new EvaluationSandboxException(409, "Conflicting evaluation reset");
    }
    return matches.getFirst();
  }

  @Transactional
  public List<EvaluationResetRequest.ProductFixture> createOrVerifyFixtures(
      String sandboxId, List<EvaluationResetRequest.ProductFixture> fixtures) {
    jdbc.batchUpdate(
        """
        INSERT IGNORE INTO eval_sandbox_product_fixture
          (sandbox_id, product_id, name, description, price_minor, currency,
           stock_quantity, available, publication_version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
        """,
        fixtures,
        fixtures.size(),
        (statement, fixture) -> {
          statement.setString(1, sandboxId);
          statement.setString(2, fixture.productId());
          statement.setString(3, fixture.name());
          statement.setString(4, fixture.description());
          statement.setLong(5, fixture.priceMinor());
          statement.setString(6, fixture.currency());
          statement.setLong(7, fixture.stockQuantity());
          statement.setBoolean(8, fixture.available());
        });
    return fixtures(sandboxId, true);
  }

  public List<EvaluationResetRequest.ProductFixture> fixtures(String sandboxId) {
    return fixtures(sandboxId, false);
  }

  public void recordSuppressedSms(String sandboxId, String correlationKey) {
    jdbc.update(
        """
        INSERT IGNORE INTO eval_sandbox_effect_stub
          (sandbox_id, effect_type, correlation_key, outcome)
        VALUES (?, 'SMS', ?, 'SUPPRESSED')
        """,
        sandboxId,
        correlationKey);
  }

  public boolean hasSuppressedSms(String sandboxId, String correlationKey) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM eval_sandbox_effect_stub
            WHERE sandbox_id = ? AND effect_type = 'SMS' AND correlation_key = ?
              AND outcome = 'SUPPRESSED'
            """,
            Integer.class,
            sandboxId,
            correlationKey);
    return count != null && count == 1;
  }

  @Transactional
  public Sandbox recordProvisioned(String sandboxId, String handle, Instant authExpiresAt) {
    Sandbox sandbox = lock(sandboxId);
    if ("PROVISIONED".equals(sandbox.authState())) {
      if (!handle.equals(sandbox.handle()) || !authExpiresAt.equals(sandbox.expiresAt())) {
        throw new EvaluationSandboxException(409, "Conflicting evaluation identity");
      }
      return sandbox;
    }
    if (!"PROVISIONING".equals(sandbox.lifecycleState())
        || !"UNPROVISIONED".equals(sandbox.authState())) {
      throw new EvaluationSandboxException(409, "Evaluation sandbox is not provisionable");
    }
    int changed =
        jdbc.update(
            """
            UPDATE eval_sandbox
            SET opaque_handle = ?, auth_invalidation_state = 'PROVISIONED',
                auth_expiry_upper_bound = ?, expires_at = ?, version = version + 1
            WHERE sandbox_id = ? AND lifecycle_state = 'PROVISIONING'
              AND auth_invalidation_state = 'UNPROVISIONED'
            """,
            handle,
            Timestamp.from(authExpiresAt),
            Timestamp.from(authExpiresAt),
            sandboxId);
    if (changed != 1) {
      throw new IllegalStateException("Evaluation principal binding did not persist");
    }
    return lock(sandboxId);
  }

  @Transactional
  public Sandbox activate(String sandboxId, Instant now) {
    Sandbox sandbox = lock(sandboxId);
    if ("ACTIVE".equals(sandbox.lifecycleState())) {
      return sandbox;
    }
    if (!"PROVISIONING".equals(sandbox.lifecycleState())
        || !"PROVISIONED".equals(sandbox.authState())
        || sandbox.handle() == null
        || sandbox.expiresAt() == null
        || !sandbox.expiresAt().isAfter(now)
        || !sandbox.provisioningDueAt().isAfter(now)) {
      throw new EvaluationSandboxException(409, "Evaluation sandbox cannot activate");
    }
    int changed =
        jdbc.update(
            """
            UPDATE eval_sandbox
            SET lifecycle_state = 'ACTIVE', activated_at = ?, cleanup_due_at = expires_at,
                version = version + 1
            WHERE sandbox_id = ? AND lifecycle_state = 'PROVISIONING'
              AND auth_invalidation_state = 'PROVISIONED'
            """,
            Timestamp.from(now),
            sandboxId);
    if (changed != 1) {
      throw new IllegalStateException("Evaluation sandbox activation did not persist");
    }
    return lock(sandboxId);
  }

  @Transactional
  public void failAfterProvisionAttempt(String sandboxId, Instant now) {
    Sandbox sandbox = lock(sandboxId);
    if ("ACTIVE".equals(sandbox.lifecycleState())) {
      throw new IllegalStateException("An active sandbox cannot be failed by reset recovery");
    }
    if ("DEAD".equals(sandbox.lifecycleState())) {
      return;
    }
    deleteFixtures(sandboxId);
    jdbc.update(
        """
        UPDATE eval_sandbox
        SET lifecycle_state = 'DEAD', death_reason = 'RESET_FAILED', dead_at = ?,
            cleanup_due_at = ?, version = version + 1
        WHERE sandbox_id = ? AND lifecycle_state = 'PROVISIONING'
        """,
        Timestamp.from(now),
        Timestamp.from(now),
        sandboxId);
  }

  @Transactional
  public Sandbox beginCompletion(
      String sandboxId, String caseCorrelation, String idempotencyKey, Instant now) {
    Sandbox sandbox = lock(sandboxId);
    if (!sandbox.caseCorrelation().equals(caseCorrelation)) {
      throw new EvaluationSandboxException(404, "Evaluation sandbox not found");
    }
    if ("PROVISIONING".equals(sandbox.lifecycleState())) {
      throw new EvaluationSandboxException(409, "Evaluation sandbox is not active");
    }
    if ("DEAD".equals(sandbox.lifecycleState())) {
      if (!"COMPLETED".equals(sandbox.deathReason())
          || !idempotencyKey.equals(sandbox.completionIdempotencyKey())) {
        throw new EvaluationSandboxException(409, "Conflicting evaluation completion");
      }
      return sandbox;
    }
    deleteFixtures(sandboxId);
    int changed =
        jdbc.update(
            """
            UPDATE eval_sandbox
            SET lifecycle_state = 'DEAD', death_reason = 'COMPLETED',
                completion_idempotency_key = ?, dead_at = ?, cleanup_due_at = ?,
                version = version + 1
            WHERE sandbox_id = ? AND lifecycle_state = 'ACTIVE'
            """,
            idempotencyKey,
            Timestamp.from(now),
            Timestamp.from(now),
            sandboxId);
    if (changed != 1) {
      throw new IllegalStateException("Evaluation completion did not persist");
    }
    return lock(sandboxId);
  }

  public boolean isActive(String sandboxId, Instant now) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM eval_sandbox
            WHERE sandbox_id = ? AND lifecycle_state = 'ACTIVE' AND expires_at > ?
            """,
            Integer.class,
            sandboxId,
            Timestamp.from(now));
    return count != null && count == 1;
  }

  public Optional<Sandbox> find(String sandboxId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM eval_sandbox WHERE sandbox_id = ?",
            EvaluationSandboxRepository::mapSandbox,
            sandboxId)
        .stream()
        .findFirst();
  }

  @Transactional
  public List<Sandbox> claimDue(Instant now, int limit, int maxAttempts, Duration retryDelay) {
    List<Sandbox> due =
        jdbc.query(
            "SELECT "
                + COLUMNS
                + " FROM eval_sandbox WHERE cleanup_due_at IS NOT NULL "
                + "AND cleanup_due_at <= ? ORDER BY cleanup_due_at, lifecycle_state, sandbox_id "
                + "LIMIT ? FOR UPDATE SKIP LOCKED",
            EvaluationSandboxRepository::mapSandbox,
            Timestamp.from(now),
            limit);
    List<Sandbox> claimed = new ArrayList<>();
    for (Sandbox sandbox : due) {
      Sandbox prepared = prepareDead(sandbox, now);
      Sandbox claim = claimInvalidation(prepared, now, maxAttempts, retryDelay);
      if (claim != null) {
        claimed.add(claim);
      }
    }
    return List.copyOf(claimed);
  }

  @Transactional
  public Optional<Sandbox> claimOne(
      String sandboxId, Instant now, int maxAttempts, Duration retryDelay) {
    Sandbox sandbox = lock(sandboxId);
    if (sandbox.cleanupDueAt() == null || sandbox.cleanupDueAt().isAfter(now)) {
      return Optional.empty();
    }
    Sandbox prepared = prepareDead(sandbox, now);
    return Optional.ofNullable(claimInvalidation(prepared, now, maxAttempts, retryDelay));
  }

  @Transactional
  public Sandbox bindCleanupHandle(String sandboxId, String handle, Instant expiresAt) {
    Sandbox sandbox = lock(sandboxId);
    if (!"DEAD".equals(sandbox.lifecycleState())) {
      throw new IllegalStateException("Cleanup handle can bind only to a dead sandbox");
    }
    if ("PROVISIONED".equals(sandbox.authState())) {
      if (!handle.equals(sandbox.handle())) {
        throw new EvaluationSandboxException(409, "Conflicting evaluation identity");
      }
      return sandbox;
    }
    if (!"UNPROVISIONED".equals(sandbox.authState())) {
      throw new IllegalStateException("Sandbox identity is already invalidated");
    }
    jdbc.update(
        """
        UPDATE eval_sandbox
        SET opaque_handle = ?, auth_invalidation_state = 'PROVISIONED',
            auth_expiry_upper_bound = ?, expires_at = ?, version = version + 1
        WHERE sandbox_id = ? AND lifecycle_state = 'DEAD'
          AND auth_invalidation_state = 'UNPROVISIONED'
        """,
        handle,
        Timestamp.from(expiresAt),
        Timestamp.from(expiresAt),
        sandboxId);
    return lock(sandboxId);
  }

  @Transactional
  public void markRevoked(String sandboxId, String handle, Instant now) {
    Sandbox sandbox = lock(sandboxId);
    if ("REVOKED".equals(sandbox.authState())) {
      return;
    }
    if (!"DEAD".equals(sandbox.lifecycleState())
        || !"PROVISIONED".equals(sandbox.authState())
        || !handle.equals(sandbox.handle())) {
      throw new IllegalStateException("Revocation result does not match sandbox truth");
    }
    int changed =
        jdbc.update(
            """
            UPDATE eval_sandbox
            SET auth_invalidation_state = 'REVOKED', closed_at = ?, cleanup_due_at = NULL,
                version = version + 1
            WHERE sandbox_id = ? AND lifecycle_state = 'DEAD'
              AND auth_invalidation_state = 'PROVISIONED' AND opaque_handle = ?
            """,
            Timestamp.from(now),
            sandboxId,
            handle);
    if (changed != 1) {
      throw new IllegalStateException("Evaluation revocation did not converge");
    }
  }

  private Sandbox prepareDead(Sandbox sandbox, Instant now) {
    if ("DEAD".equals(sandbox.lifecycleState())) {
      return sandbox;
    }
    String reason = "ACTIVE".equals(sandbox.lifecycleState()) ? "EXPIRED" : "ABANDONED";
    deleteFixtures(sandbox.sandboxId());
    jdbc.update(
        """
        UPDATE eval_sandbox
        SET lifecycle_state = 'DEAD', death_reason = ?, dead_at = ?, cleanup_due_at = ?,
            version = version + 1
        WHERE sandbox_id = ? AND lifecycle_state IN ('PROVISIONING', 'ACTIVE')
        """,
        reason,
        Timestamp.from(now),
        Timestamp.from(now),
        sandbox.sandboxId());
    return lock(sandbox.sandboxId());
  }

  private Sandbox claimInvalidation(
      Sandbox sandbox, Instant now, int maxAttempts, Duration retryDelay) {
    if (isFinalInvalidation(sandbox.authState())) {
      closeWithoutExternalCall(sandbox.sandboxId(), now, sandbox.authState());
      return null;
    }
    if (!sandbox.authExpiryUpperBound().isAfter(now)) {
      closeWithoutExternalCall(sandbox.sandboxId(), now, "EXPIRY_PROVEN");
      return null;
    }
    if (sandbox.cleanupAttempts() >= maxAttempts) {
      jdbc.update(
          "UPDATE eval_sandbox SET cleanup_due_at = ? WHERE sandbox_id = ?",
          Timestamp.from(sandbox.authExpiryUpperBound()),
          sandbox.sandboxId());
      return null;
    }
    Instant nextAttempt = now.plus(retryDelay);
    if (nextAttempt.isAfter(sandbox.authExpiryUpperBound())) {
      nextAttempt = sandbox.authExpiryUpperBound();
    }
    jdbc.update(
        """
        UPDATE eval_sandbox
        SET cleanup_attempts = cleanup_attempts + 1, cleanup_due_at = ?, version = version + 1
        WHERE sandbox_id = ? AND lifecycle_state = 'DEAD'
        """,
        Timestamp.from(nextAttempt),
        sandbox.sandboxId());
    return lock(sandbox.sandboxId());
  }

  private void closeWithoutExternalCall(String sandboxId, Instant now, String authState) {
    jdbc.update(
        """
        UPDATE eval_sandbox
        SET auth_invalidation_state = ?, closed_at = COALESCE(closed_at, ?),
            cleanup_due_at = NULL, version = version + 1
        WHERE sandbox_id = ? AND lifecycle_state = 'DEAD'
        """,
        authState,
        Timestamp.from(now),
        sandboxId);
  }

  private List<EvaluationResetRequest.ProductFixture> fixtures(String sandboxId, boolean locking) {
    String suffix = locking ? " FOR SHARE" : "";
    return jdbc.query(
        """
        SELECT product_id, name, description, price_minor, currency, stock_quantity, available
        FROM eval_sandbox_product_fixture
        WHERE sandbox_id = ? ORDER BY product_id
        """
            + suffix,
        (result, row) ->
            new EvaluationResetRequest.ProductFixture(
                result.getString("product_id"),
                result.getString("name"),
                result.getString("description"),
                result.getLong("price_minor"),
                result.getString("currency"),
                result.getLong("stock_quantity"),
                result.getBoolean("available")),
        sandboxId);
  }

  private void deleteFixtures(String sandboxId) {
    jdbc.update("DELETE FROM eval_sandbox_product_fixture WHERE sandbox_id = ?", sandboxId);
  }

  private Sandbox lock(String sandboxId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM eval_sandbox WHERE sandbox_id = ? FOR UPDATE",
            EvaluationSandboxRepository::mapSandbox,
            sandboxId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new EvaluationSandboxException(404, "Evaluation sandbox not found"));
  }

  private static boolean sameReset(Sandbox sandbox, NewSandbox request) {
    return sandbox.sandboxId().equals(request.sandboxId())
        && sandbox.caseCorrelation().equals(request.caseCorrelation())
        && sandbox.resetIdempotencyKey().equals(request.resetIdempotencyKey())
        && sandbox.fixtureDigest().equals(request.fixtureDigest())
        && sandbox.fixtureCount() == request.fixtureCount()
        && sandbox.testUserLabel().equals(request.testUserLabel())
        && sandbox.ttlSeconds() == request.ttlSeconds()
        && sandbox.provisionIdempotencyKey().equals(request.provisionIdempotencyKey())
        && sandbox.revokeIdempotencyKey().equals(request.revokeIdempotencyKey());
  }

  private static boolean isFinalInvalidation(String state) {
    return "REVOKED".equals(state) || "EXPIRY_PROVEN".equals(state);
  }

  private static Sandbox mapSandbox(ResultSet result, int row) throws SQLException {
    return new Sandbox(
        result.getString("sandbox_id"),
        result.getString("case_correlation"),
        result.getString("reset_idempotency_key"),
        result.getString("fixture_digest"),
        result.getInt("fixture_count"),
        result.getString("test_user_label"),
        result.getInt("requested_ttl_seconds"),
        result.getString("auth_provision_idempotency_key"),
        result.getString("auth_revoke_idempotency_key"),
        result.getString("opaque_handle"),
        result.getString("lifecycle_state"),
        result.getString("auth_invalidation_state"),
        result.getString("death_reason"),
        result.getString("completion_idempotency_key"),
        result.getInt("cleanup_attempts"),
        instant(result, "cleanup_due_at"),
        instant(result, "provisioning_due_at"),
        instant(result, "auth_expiry_upper_bound"),
        instant(result, "expires_at"),
        instant(result, "activated_at"),
        instant(result, "dead_at"),
        instant(result, "closed_at"),
        result.getLong("version"));
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  public record NewSandbox(
      String sandboxId,
      String caseCorrelation,
      String resetIdempotencyKey,
      String fixtureDigest,
      int fixtureCount,
      String testUserLabel,
      int ttlSeconds,
      String provisionIdempotencyKey,
      String revokeIdempotencyKey,
      Instant provisioningDueAt,
      Instant authExpiryUpperBound) {}

  public record Sandbox(
      String sandboxId,
      String caseCorrelation,
      String resetIdempotencyKey,
      String fixtureDigest,
      int fixtureCount,
      String testUserLabel,
      int ttlSeconds,
      String provisionIdempotencyKey,
      String revokeIdempotencyKey,
      String handle,
      String lifecycleState,
      String authState,
      String deathReason,
      String completionIdempotencyKey,
      int cleanupAttempts,
      Instant cleanupDueAt,
      Instant provisioningDueAt,
      Instant authExpiryUpperBound,
      Instant expiresAt,
      Instant activatedAt,
      Instant deadAt,
      Instant closedAt,
      long version) {}
}
