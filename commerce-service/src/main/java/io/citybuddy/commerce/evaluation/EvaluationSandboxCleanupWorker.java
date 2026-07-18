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
      Sandbox bound = claimed;
      if ("UNPROVISIONED".equals(bound.authState())) {
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
      repository.markRevoked(bound.sandboxId(), bound.handle(), clock.instant());
      return true;
    } catch (HttpEvaluationIdentityClient.EvaluationIdentityUnavailableException exception) {
      return false;
    }
  }
}
