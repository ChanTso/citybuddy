package io.citybuddy.commerce.evaluation;

import io.citybuddy.commerce.evaluation.EvaluationSandboxRepository.Sandbox;
import java.time.Clock;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;

public final class EvaluationSandboxCleanupWorker {
  private final EvaluationSandboxRepository repository;
  private final EvaluationIdentityClient identity;
  private final EvaluationSandboxProperties properties;
  private final Clock clock;

  public EvaluationSandboxCleanupWorker(
      EvaluationSandboxRepository repository,
      EvaluationIdentityClient identity,
      EvaluationSandboxProperties properties,
      Clock clock) {
    this.repository = repository;
    this.identity = identity;
    this.properties = properties;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${citybuddy.evaluation.janitor-interval:5s}")
  public void runDueBatch() {
    List<Sandbox> claimed =
        repository.claimDue(
            clock.instant(),
            properties.janitorBatchSize(),
            properties.maxCleanupAttempts(),
            properties.cleanupRetry());
    for (Sandbox sandbox : claimed) {
      invalidate(sandbox);
    }
  }

  public boolean cleanupNow(String sandboxId) {
    return repository
        .claimOne(
            sandboxId, clock.instant(), properties.maxCleanupAttempts(), properties.cleanupRetry())
        .map(this::invalidate)
        .orElseGet(
            () -> repository.find(sandboxId).map(item -> item.closedAt() != null).orElse(false));
  }

  private boolean invalidate(Sandbox claimed) {
    try {
      Sandbox current = invalidatePrimary(claimed);
      current = invalidatePaymentOwner(current);
      return current.closedAt() != null;
    } catch (HttpEvaluationIdentityClient.EvaluationIdentityUnavailableException exception) {
      return false;
    }
  }

  private Sandbox invalidatePrimary(Sandbox sandbox) {
    Sandbox bound = sandbox;
    if (isFinal(bound.authState())) {
      return bound;
    }
    if ("UNPROVISIONED".equals(bound.authState())) {
      if (!provisioningRecoveryOpen(bound)) {
        return bound;
      }
      EvaluationIdentityClient.Provisioned provisioned =
          identity.provision(
              bound.sandboxId(),
              bound.caseCorrelation(),
              bound.testUserLabel(),
              bound.ttlSeconds(),
              bound.provisionIdempotencyKey());
      bound =
          repository.bindCleanupHandle(
              bound.sandboxId(), provisioned.handle(), provisioned.expiresAt());
    }
    if (!"PROVISIONED".equals(bound.authState()) || bound.handle() == null) {
      throw new IllegalStateException("Claimed cleanup has no revocable identity");
    }
    identity.revoke(
        bound.handle(), bound.sandboxId(), bound.caseCorrelation(), bound.revokeIdempotencyKey());
    return repository.markRevoked(bound.sandboxId(), bound.handle(), clock.instant());
  }

  private Sandbox invalidatePaymentOwner(Sandbox sandbox) {
    if (sandbox.paymentOwnerTestUserLabel() == null || isFinal(sandbox.paymentOwnerAuthState())) {
      return sandbox;
    }
    Sandbox bound = sandbox;
    if ("UNPROVISIONED".equals(bound.paymentOwnerAuthState())) {
      if (!provisioningRecoveryOpen(bound)) {
        return bound;
      }
      EvaluationIdentityClient.Provisioned provisioned =
          identity.provision(
              bound.sandboxId(),
              bound.paymentOwnerCaseCorrelation(),
              bound.paymentOwnerTestUserLabel(),
              bound.ttlSeconds(),
              bound.paymentOwnerProvisionIdempotencyKey());
      bound =
          repository.bindPaymentOwnerCleanupHandle(
              bound.sandboxId(), provisioned.handle(), provisioned.expiresAt());
    }
    if (!"PROVISIONED".equals(bound.paymentOwnerAuthState())
        || bound.paymentOwnerHandle() == null) {
      throw new IllegalStateException("Claimed cleanup has no revocable payment owner");
    }
    identity.revoke(
        bound.paymentOwnerHandle(),
        bound.sandboxId(),
        bound.paymentOwnerCaseCorrelation(),
        bound.paymentOwnerRevokeIdempotencyKey());
    return repository.markPaymentOwnerRevoked(
        bound.sandboxId(), bound.paymentOwnerHandle(), clock.instant());
  }

  private boolean provisioningRecoveryOpen(Sandbox sandbox) {
    return sandbox.provisioningDueAt().isAfter(clock.instant());
  }

  private static boolean isFinal(String state) {
    return "REVOKED".equals(state) || "EXPIRY_PROVEN".equals(state);
  }
}
