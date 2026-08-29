package io.citybuddy.commerce.evaluation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class EvaluationSandboxRepository {
  private static final Pattern OPAQUE_HANDLE = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final String COLUMNS =
      "sandbox_id, case_correlation, reset_idempotency_key, fixture_digest, fixture_count, "
          + "test_user_label, requested_ttl_seconds, auth_provision_idempotency_key, "
          + "auth_revoke_idempotency_key, opaque_handle, lifecycle_state, "
          + "auth_invalidation_state, payment_owner_test_user_label, "
          + "payment_owner_case_correlation, payment_owner_auth_provision_idempotency_key, "
          + "payment_owner_auth_revoke_idempotency_key, payment_owner_opaque_handle, "
          + "payment_owner_auth_invalidation_state, payment_owner_auth_expiry_upper_bound, "
          + "payment_owner_expires_at, death_reason, completion_idempotency_key, "
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
           auth_revoke_idempotency_key, payment_owner_test_user_label,
           payment_owner_case_correlation, payment_owner_auth_provision_idempotency_key,
           payment_owner_auth_revoke_idempotency_key, payment_owner_auth_invalidation_state,
           payment_owner_auth_expiry_upper_bound, lifecycle_state, auth_invalidation_state,
           cleanup_due_at, provisioning_due_at, auth_expiry_upper_bound)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                'PROVISIONING', 'UNPROVISIONED', ?, ?, ?)
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
        request.paymentOwnerTestUserLabel(),
        request.paymentOwnerCaseCorrelation(),
        request.paymentOwnerProvisionIdempotencyKey(),
        request.paymentOwnerRevokeIdempotencyKey(),
        request.paymentOwnerTestUserLabel() == null ? null : "UNPROVISIONED",
        timestamp(request.paymentOwnerAuthExpiryUpperBound()),
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

  public void verifyPaymentOrder(
      String sandboxId,
      String ownerHandle,
      EvaluationResetRequest.PaymentOrderFixture paymentOrder) {
    if (paymentOrder == null) {
      return;
    }
    PaymentOrderTruth expected = expectedPaymentOrder(sandboxId, ownerHandle, paymentOrder, false);
    verifyPaymentOrder(expected);
  }

  private void createOrVerifyPaymentOrder(
      String sandboxId,
      String ownerHandle,
      EvaluationResetRequest.PaymentOrderFixture paymentOrder) {
    if (paymentOrder == null) {
      return;
    }
    PaymentOrderTruth expected = expectedPaymentOrder(sandboxId, ownerHandle, paymentOrder, true);
    jdbc.update(
        """
        INSERT IGNORE INTO standard_order
          (order_id, user_subject, sandbox_id, evaluation_owner_handle, product_id, product_name,
           unit_price_minor, currency, quantity, total_price_minor, product_version, status,
           state_version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'UNPAID', 1)
        """,
        expected.orderId(),
        fixtureOwner(ownerHandle),
        sandboxId,
        ownerHandle,
        expected.productId(),
        expected.productName(),
        expected.unitPriceMinor(),
        expected.currency(),
        expected.quantity(),
        expected.totalPriceMinor());
    verifyPaymentOrder(expected);
  }

  private PaymentOrderTruth expectedPaymentOrder(
      String sandboxId,
      String ownerHandle,
      EvaluationResetRequest.PaymentOrderFixture paymentOrder,
      boolean lockFixture) {
    EvaluationResetRequest.ProductFixture product =
        fixtures(sandboxId, lockFixture).stream()
            .filter(item -> item.productId().equals(paymentOrder.productId()))
            .findFirst()
            .orElseThrow(() -> new EvaluationSandboxException(409, "Payment fixture is missing"));
    long total = Math.multiplyExact(product.priceMinor(), paymentOrder.quantity());
    return new PaymentOrderTruth(
        paymentOrder.orderId(),
        sandboxId,
        ownerHandle,
        product.productId(),
        product.name(),
        product.priceMinor(),
        product.currency(),
        paymentOrder.quantity(),
        total,
        1);
  }

  private void verifyPaymentOrder(PaymentOrderTruth expected) {
    List<PaymentOrderTruth> orders =
        jdbc.query(
            """
            SELECT order_id, sandbox_id, evaluation_owner_handle, product_id, product_name,
                   unit_price_minor, currency, quantity, total_price_minor, product_version
            FROM standard_order WHERE order_id = ? FOR SHARE
            """,
            (result, row) ->
                new PaymentOrderTruth(
                    result.getString("order_id"),
                    result.getString("sandbox_id"),
                    result.getString("evaluation_owner_handle"),
                    result.getString("product_id"),
                    result.getString("product_name"),
                    result.getLong("unit_price_minor"),
                    result.getString("currency"),
                    result.getInt("quantity"),
                    result.getLong("total_price_minor"),
                    result.getLong("product_version")),
            expected.orderId());
    if (orders.size() != 1 || !expected.equals(orders.getFirst())) {
      throw new EvaluationSandboxException(409, "Conflicting payment order fixture");
    }
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
      if (!handle.equals(sandbox.handle())
          || !authExpiresAt.equals(sandbox.authExpiryUpperBound())) {
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
  public Sandbox recordPaymentOwnerProvisioned(
      String sandboxId, String handle, Instant authExpiresAt) {
    Sandbox sandbox = lock(sandboxId);
    if (sandbox.paymentOwnerTestUserLabel() == null) {
      throw new EvaluationSandboxException(409, "Evaluation payment owner was not requested");
    }
    if ("PROVISIONED".equals(sandbox.paymentOwnerAuthState())) {
      if (!handle.equals(sandbox.paymentOwnerHandle())
          || !authExpiresAt.equals(sandbox.paymentOwnerAuthExpiryUpperBound())) {
        throw new EvaluationSandboxException(409, "Conflicting evaluation payment owner");
      }
      return sandbox;
    }
    if (!"PROVISIONING".equals(sandbox.lifecycleState())
        || !"PROVISIONED".equals(sandbox.authState())
        || !"UNPROVISIONED".equals(sandbox.paymentOwnerAuthState())
        || handle.equals(sandbox.handle())) {
      throw new EvaluationSandboxException(409, "Evaluation payment owner is not provisionable");
    }
    int changed =
        jdbc.update(
            """
            UPDATE eval_sandbox
            SET payment_owner_opaque_handle = ?,
                payment_owner_auth_invalidation_state = 'PROVISIONED',
                payment_owner_auth_expiry_upper_bound = ?, payment_owner_expires_at = ?,
                expires_at = LEAST(expires_at, ?), version = version + 1
            WHERE sandbox_id = ? AND lifecycle_state = 'PROVISIONING'
              AND auth_invalidation_state = 'PROVISIONED'
              AND payment_owner_auth_invalidation_state = 'UNPROVISIONED'
            """,
            handle,
            Timestamp.from(authExpiresAt),
            Timestamp.from(authExpiresAt),
            Timestamp.from(authExpiresAt),
            sandboxId);
    if (changed != 1) {
      throw new IllegalStateException("Evaluation payment owner binding did not persist");
    }
    return lock(sandboxId);
  }

  @Transactional
  public Sandbox activateWithPaymentOrder(
      String sandboxId,
      String ownerHandle,
      EvaluationResetRequest.PaymentOrderFixture paymentOrder,
      Instant now) {
    Sandbox sandbox = lock(sandboxId);
    String expectedOwnerHandle = paymentOrderOwnerHandle(sandbox);
    if (!expectedOwnerHandle.equals(ownerHandle)) {
      throw new EvaluationSandboxException(409, "Conflicting payment order owner");
    }
    if ("ACTIVE".equals(sandbox.lifecycleState())) {
      verifyPaymentOrder(sandboxId, ownerHandle, paymentOrder);
      return sandbox;
    }
    if (!"PROVISIONING".equals(sandbox.lifecycleState())
        || !"PROVISIONED".equals(sandbox.authState())
        || sandbox.handle() == null
        || sandbox.expiresAt() == null
        || !sandbox.expiresAt().isAfter(now)
        || !sandbox.provisioningDueAt().isAfter(now)
        || !paymentOwnerProvisioned(sandbox)) {
      throw new EvaluationSandboxException(409, "Evaluation sandbox cannot activate");
    }
    createOrVerifyPaymentOrder(sandboxId, ownerHandle, paymentOrder);
    int changed =
        jdbc.update(
            """
            UPDATE eval_sandbox
            SET lifecycle_state = 'ACTIVE', activated_at = ?, cleanup_due_at = expires_at,
                version = version + 1
            WHERE sandbox_id = ? AND lifecycle_state = 'PROVISIONING'
              AND auth_invalidation_state = 'PROVISIONED'
              AND (payment_owner_auth_invalidation_state IS NULL
                   OR payment_owner_auth_invalidation_state = 'PROVISIONED')
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

  public Sandbox lockForPayment(String sandboxId) {
    List<Sandbox> matches =
        jdbc.query(
            "SELECT " + COLUMNS + " FROM eval_sandbox WHERE sandbox_id = ? FOR UPDATE",
            EvaluationSandboxRepository::mapSandbox,
            sandboxId);
    if (matches.size() != 1) {
      throw new EvaluationSandboxException(
          403,
          EvaluationRejectionReason.PAYMENT_SANDBOX_NOT_FOUND,
          "Evaluation sandbox is inactive");
    }
    return matches.getFirst();
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
      if (!handle.equals(sandbox.handle()) || !expiresAt.equals(sandbox.authExpiryUpperBound())) {
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
            auth_expiry_upper_bound = ?,
            expires_at = CASE
              WHEN payment_owner_expires_at IS NULL THEN ?
              ELSE LEAST(?, payment_owner_expires_at)
            END,
            version = version + 1
        WHERE sandbox_id = ? AND lifecycle_state = 'DEAD'
          AND auth_invalidation_state = 'UNPROVISIONED'
        """,
        handle,
        Timestamp.from(expiresAt),
        Timestamp.from(expiresAt),
        Timestamp.from(expiresAt),
        sandboxId);
    return lock(sandboxId);
  }

  @Transactional
  public Sandbox bindPaymentOwnerCleanupHandle(String sandboxId, String handle, Instant expiresAt) {
    Sandbox sandbox = lock(sandboxId);
    if (!"DEAD".equals(sandbox.lifecycleState()) || sandbox.paymentOwnerTestUserLabel() == null) {
      throw new IllegalStateException(
          "Payment-owner cleanup handle can bind only to a dead sandbox that requested one");
    }
    if ("PROVISIONED".equals(sandbox.paymentOwnerAuthState())) {
      if (!handle.equals(sandbox.paymentOwnerHandle())
          || !expiresAt.equals(sandbox.paymentOwnerAuthExpiryUpperBound())) {
        throw new EvaluationSandboxException(409, "Conflicting evaluation payment owner");
      }
      return sandbox;
    }
    if (!"UNPROVISIONED".equals(sandbox.paymentOwnerAuthState())
        || handle.equals(sandbox.handle())) {
      throw new IllegalStateException("Sandbox payment owner is already invalidated");
    }
    jdbc.update(
        """
        UPDATE eval_sandbox
        SET payment_owner_opaque_handle = ?,
            payment_owner_auth_invalidation_state = 'PROVISIONED',
            payment_owner_auth_expiry_upper_bound = ?, payment_owner_expires_at = ?,
            expires_at = CASE WHEN expires_at IS NULL THEN ? ELSE LEAST(expires_at, ?) END,
            version = version + 1
        WHERE sandbox_id = ? AND lifecycle_state = 'DEAD'
          AND payment_owner_auth_invalidation_state = 'UNPROVISIONED'
        """,
        handle,
        Timestamp.from(expiresAt),
        Timestamp.from(expiresAt),
        Timestamp.from(expiresAt),
        Timestamp.from(expiresAt),
        sandboxId);
    return lock(sandboxId);
  }

  @Transactional
  public Sandbox markRevoked(String sandboxId, String handle, Instant now) {
    Sandbox sandbox = lock(sandboxId);
    if ("REVOKED".equals(sandbox.authState())) {
      return sandbox;
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
            SET auth_invalidation_state = 'REVOKED', version = version + 1
            WHERE sandbox_id = ? AND lifecycle_state = 'DEAD'
              AND auth_invalidation_state = 'PROVISIONED' AND opaque_handle = ?
            """,
            sandboxId,
            handle);
    if (changed != 1) {
      throw new IllegalStateException("Evaluation revocation did not converge");
    }
    return closeIfSafe(sandboxId, now);
  }

  @Transactional
  public Sandbox markPaymentOwnerRevoked(String sandboxId, String handle, Instant now) {
    Sandbox sandbox = lock(sandboxId);
    if ("REVOKED".equals(sandbox.paymentOwnerAuthState())) {
      return sandbox;
    }
    if (!"DEAD".equals(sandbox.lifecycleState())
        || !"PROVISIONED".equals(sandbox.paymentOwnerAuthState())
        || !handle.equals(sandbox.paymentOwnerHandle())) {
      throw new IllegalStateException(
          "Payment-owner revocation result does not match sandbox truth");
    }
    int changed =
        jdbc.update(
            """
            UPDATE eval_sandbox
            SET payment_owner_auth_invalidation_state = 'REVOKED', version = version + 1
            WHERE sandbox_id = ? AND lifecycle_state = 'DEAD'
              AND payment_owner_auth_invalidation_state = 'PROVISIONED'
              AND payment_owner_opaque_handle = ?
            """,
            sandboxId,
            handle);
    if (changed != 1) {
      throw new IllegalStateException("Evaluation payment-owner revocation did not converge");
    }
    return closeIfSafe(sandboxId, now);
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
    Sandbox current = proveExpiredIdentities(sandbox, now);
    if (allIdentitiesInvalidated(current)) {
      closeIfSafe(current.sandboxId(), now);
      return null;
    }
    if (current.cleanupAttempts() >= maxAttempts) {
      jdbc.update(
          "UPDATE eval_sandbox SET cleanup_due_at = ? WHERE sandbox_id = ?",
          Timestamp.from(latestUnresolvedExpiry(current)),
          current.sandboxId());
      return null;
    }
    Instant nextAttempt = now.plus(retryDelay);
    Instant unresolvedExpiry = latestUnresolvedExpiry(current);
    if (nextAttempt.isAfter(unresolvedExpiry)) {
      nextAttempt = unresolvedExpiry;
    }
    jdbc.update(
        """
        UPDATE eval_sandbox
        SET cleanup_attempts = cleanup_attempts + 1, cleanup_due_at = ?, version = version + 1
        WHERE sandbox_id = ? AND lifecycle_state = 'DEAD'
        """,
        Timestamp.from(nextAttempt),
        current.sandboxId());
    return lock(current.sandboxId());
  }

  private Sandbox proveExpiredIdentities(Sandbox sandbox, Instant now) {
    boolean primaryExpired =
        !isFinalInvalidation(sandbox.authState()) && !sandbox.authExpiryUpperBound().isAfter(now);
    boolean paymentOwnerExpired =
        sandbox.paymentOwnerTestUserLabel() != null
            && !isFinalInvalidation(sandbox.paymentOwnerAuthState())
            && !sandbox.paymentOwnerAuthExpiryUpperBound().isAfter(now);
    if (!primaryExpired && !paymentOwnerExpired) {
      return sandbox;
    }
    jdbc.update(
        """
        UPDATE eval_sandbox
        SET auth_invalidation_state = CASE
              WHEN ? THEN 'EXPIRY_PROVEN' ELSE auth_invalidation_state
            END,
            payment_owner_auth_invalidation_state = CASE
              WHEN ? THEN 'EXPIRY_PROVEN' ELSE payment_owner_auth_invalidation_state
            END,
            version = version + 1
        WHERE sandbox_id = ? AND lifecycle_state = 'DEAD'
        """,
        primaryExpired,
        paymentOwnerExpired,
        sandbox.sandboxId());
    return lock(sandbox.sandboxId());
  }

  private Sandbox closeIfSafe(String sandboxId, Instant now) {
    Sandbox sandbox = lock(sandboxId);
    if (!"DEAD".equals(sandbox.lifecycleState())) {
      throw new IllegalStateException("Only a dead evaluation sandbox can close");
    }
    if (!allIdentitiesInvalidated(sandbox)) {
      return sandbox;
    }
    jdbc.update(
        """
        UPDATE eval_sandbox
        SET closed_at = COALESCE(closed_at, ?), cleanup_due_at = NULL,
            version = version + 1
        WHERE sandbox_id = ? AND lifecycle_state = 'DEAD' AND closed_at IS NULL
        """,
        Timestamp.from(now),
        sandboxId);
    return lock(sandboxId);
  }

  private static Instant latestUnresolvedExpiry(Sandbox sandbox) {
    Instant latest =
        isFinalInvalidation(sandbox.authState()) ? null : sandbox.authExpiryUpperBound();
    if (sandbox.paymentOwnerTestUserLabel() != null
        && !isFinalInvalidation(sandbox.paymentOwnerAuthState())
        && (latest == null || sandbox.paymentOwnerAuthExpiryUpperBound().isAfter(latest))) {
      latest = sandbox.paymentOwnerAuthExpiryUpperBound();
    }
    if (latest == null) {
      throw new IllegalStateException("Closed evaluation sandbox has no unresolved expiry");
    }
    return latest;
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
        && sandbox.revokeIdempotencyKey().equals(request.revokeIdempotencyKey())
        && java.util.Objects.equals(
            sandbox.paymentOwnerTestUserLabel(), request.paymentOwnerTestUserLabel())
        && java.util.Objects.equals(
            sandbox.paymentOwnerCaseCorrelation(), request.paymentOwnerCaseCorrelation())
        && java.util.Objects.equals(
            sandbox.paymentOwnerProvisionIdempotencyKey(),
            request.paymentOwnerProvisionIdempotencyKey())
        && java.util.Objects.equals(
            sandbox.paymentOwnerRevokeIdempotencyKey(), request.paymentOwnerRevokeIdempotencyKey());
  }

  private static boolean isFinalInvalidation(String state) {
    return "REVOKED".equals(state) || "EXPIRY_PROVEN".equals(state);
  }

  public static boolean allIdentitiesInvalidated(Sandbox sandbox) {
    return isFinalInvalidation(sandbox.authState())
        && (sandbox.paymentOwnerTestUserLabel() == null
            || isFinalInvalidation(sandbox.paymentOwnerAuthState()));
  }

  public static String paymentOrderOwnerHandle(Sandbox sandbox) {
    return sandbox.paymentOwnerTestUserLabel() == null
        ? sandbox.handle()
        : sandbox.paymentOwnerHandle();
  }

  private static boolean paymentOwnerProvisioned(Sandbox sandbox) {
    return sandbox.paymentOwnerTestUserLabel() == null
        || ("PROVISIONED".equals(sandbox.paymentOwnerAuthState())
            && sandbox.paymentOwnerHandle() != null
            && sandbox.paymentOwnerExpiresAt() != null);
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
        result.getString("payment_owner_test_user_label"),
        result.getString("payment_owner_case_correlation"),
        result.getString("payment_owner_auth_provision_idempotency_key"),
        result.getString("payment_owner_auth_revoke_idempotency_key"),
        result.getString("payment_owner_opaque_handle"),
        result.getString("payment_owner_auth_invalidation_state"),
        instant(result, "payment_owner_auth_expiry_upper_bound"),
        instant(result, "payment_owner_expires_at"),
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

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
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
      String paymentOwnerTestUserLabel,
      String paymentOwnerCaseCorrelation,
      String paymentOwnerProvisionIdempotencyKey,
      String paymentOwnerRevokeIdempotencyKey,
      Instant provisioningDueAt,
      Instant authExpiryUpperBound,
      Instant paymentOwnerAuthExpiryUpperBound) {}

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
      String paymentOwnerTestUserLabel,
      String paymentOwnerCaseCorrelation,
      String paymentOwnerProvisionIdempotencyKey,
      String paymentOwnerRevokeIdempotencyKey,
      String paymentOwnerHandle,
      String paymentOwnerAuthState,
      Instant paymentOwnerAuthExpiryUpperBound,
      Instant paymentOwnerExpiresAt,
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

  private record PaymentOrderTruth(
      String orderId,
      String sandboxId,
      String ownerHandle,
      String productId,
      String productName,
      long unitPriceMinor,
      String currency,
      int quantity,
      long totalPriceMinor,
      long productVersion) {}

  public static String fixtureOwner(String ownerHandle) {
    return tryFixtureOwner(ownerHandle)
        .orElseThrow(
            () -> new EvaluationSandboxException(409, "Evaluation identity handle is invalid"));
  }

  public static Optional<String> tryFixtureOwner(String ownerHandle) {
    if (ownerHandle == null || !OPAQUE_HANDLE.matcher(ownerHandle).matches()) {
      return Optional.empty();
    }
    return Optional.of("eval-handle:" + ownerHandle);
  }
}
