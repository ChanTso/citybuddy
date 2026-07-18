package io.citybuddy.commerce.evaluation;

import io.citybuddy.commerce.evaluation.EvaluationSandboxRepository.NewSandbox;
import io.citybuddy.commerce.evaluation.EvaluationSandboxRepository.Sandbox;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

public final class EvaluationSandboxService {
  private final EvaluationSandboxRepository repository;
  private final EvaluationIdentityClient identity;
  private final EvaluationSandboxCleanupWorker cleanup;
  private final EvaluationSandboxProperties properties;
  private final Clock clock;

  public EvaluationSandboxService(
      EvaluationSandboxRepository repository,
      EvaluationIdentityClient identity,
      EvaluationSandboxCleanupWorker cleanup,
      EvaluationSandboxProperties properties,
      Clock clock) {
    this.repository = repository;
    this.identity = identity;
    this.cleanup = cleanup;
    this.properties = properties;
    this.clock = clock;
  }

  public ResetResult reset(EvaluationResetRequest request, String idempotencyKey) {
    String boundedKey =
        EvaluationRequestParser.boundedHeader(idempotencyKey, 128, "Invalid reset idempotency key");
    String digest = EvaluationRequestParser.fixtureDigest(request.products());
    Instant now = clock.instant();
    Instant provisioningDue = now.plus(properties.provisioningTimeout());
    String provisionKey =
        EvaluationRequestParser.derivedIdempotencyKey(
            "provision", request.sandboxId(), request.caseCorrelation());
    String revokeKey =
        EvaluationRequestParser.derivedIdempotencyKey(
            "revoke", request.sandboxId(), request.caseCorrelation());
    Sandbox sandbox =
        repository.registerOrLoad(
            new NewSandbox(
                request.sandboxId(),
                request.caseCorrelation(),
                boundedKey,
                digest,
                request.products().size(),
                request.testUserLabel(),
                request.ttlSeconds(),
                provisionKey,
                revokeKey,
                provisioningDue,
                provisioningDue
                    .plusSeconds(request.ttlSeconds())
                    .plus(properties.authExpirySafety())));
    if ("ACTIVE".equals(sandbox.lifecycleState())) {
      if (sandbox.expiresAt() == null || !sandbox.expiresAt().isAfter(now)) {
        throw new EvaluationSandboxException(409, "Evaluation sandbox is no longer active");
      }
      return result(sandbox);
    }
    if (!"PROVISIONING".equals(sandbox.lifecycleState())) {
      throw new EvaluationSandboxException(409, "Evaluation sandbox cannot be reused");
    }

    try {
      List<EvaluationResetRequest.ProductFixture> persisted =
          repository.createOrVerifyFixtures(request.sandboxId(), request.products());
      verifyFixtureClosure(sandbox, persisted);
      repository.recordSuppressedSms(request.sandboxId(), sandbox.resetIdempotencyKey());
      if (!repository.hasSuppressedSms(request.sandboxId(), sandbox.resetIdempotencyKey())) {
        throw new IllegalStateException("Evaluation effect stub did not persist");
      }
    } catch (RuntimeException exception) {
      repository.failAfterProvisionAttempt(request.sandboxId(), clock.instant());
      cleanup.cleanupNow(request.sandboxId());
      if (exception instanceof EvaluationSandboxException sandboxException) {
        throw sandboxException;
      }
      throw new EvaluationSandboxException(503, "Evaluation fixture provisioning failed");
    }

    if (!sandbox.provisioningDueAt().isAfter(clock.instant())) {
      repository.failAfterProvisionAttempt(request.sandboxId(), clock.instant());
      cleanup.cleanupNow(request.sandboxId());
      throw new EvaluationSandboxException(503, "Evaluation provisioning deadline elapsed");
    }

    EvaluationIdentityClient.Provisioned provisioned;
    try {
      provisioned =
          identity.provision(
              sandbox.sandboxId(),
              sandbox.caseCorrelation(),
              sandbox.testUserLabel(),
              sandbox.ttlSeconds(),
              sandbox.provisionIdempotencyKey());
    } catch (HttpEvaluationIdentityClient.EvaluationIdentityUnavailableException exception) {
      repository.failAfterProvisionAttempt(request.sandboxId(), clock.instant());
      cleanup.cleanupNow(request.sandboxId());
      throw new EvaluationSandboxException(502, "Evaluation identity provisioning failed");
    }

    try {
      Instant afterProvision = clock.instant();
      if (!provisioned.expiresAt().isAfter(afterProvision)
          || !sandbox.provisioningDueAt().isAfter(afterProvision)) {
        throw new EvaluationSandboxException(503, "Evaluation provisioning deadline elapsed");
      }
      Sandbox bound =
          repository.recordProvisioned(
              request.sandboxId(), provisioned.handle(), provisioned.expiresAt());
      verifyFixtureClosure(bound, repository.fixtures(request.sandboxId()));
      if (!repository.hasSuppressedSms(request.sandboxId(), bound.resetIdempotencyKey())) {
        throw new IllegalStateException("Evaluation effect stub is incomplete");
      }
      return result(repository.activate(request.sandboxId(), clock.instant()));
    } catch (RuntimeException exception) {
      repository.failAfterProvisionAttempt(request.sandboxId(), clock.instant());
      cleanup.cleanupNow(request.sandboxId());
      if (exception instanceof EvaluationSandboxException sandboxException) {
        throw sandboxException;
      }
      throw new EvaluationSandboxException(503, "Evaluation activation failed");
    }
  }

  public CompletionResult complete(
      String sandboxId, String caseCorrelation, String idempotencyKey) {
    String boundedSandbox = EvaluationRequestParser.boundedHeader(sandboxId, 64, "Invalid sandbox");
    String boundedKey =
        EvaluationRequestParser.boundedHeader(
            idempotencyKey, 128, "Invalid completion idempotency key");
    Sandbox dead =
        repository.beginCompletion(boundedSandbox, caseCorrelation, boundedKey, clock.instant());
    if (dead.closedAt() == null) {
      cleanup.cleanupNow(boundedSandbox);
      dead =
          repository
              .find(boundedSandbox)
              .orElseThrow(() -> new IllegalStateException("Completed sandbox disappeared"));
    }
    if (dead.closedAt() == null
        || !("REVOKED".equals(dead.authState()) || "EXPIRY_PROVEN".equals(dead.authState()))) {
      throw new EvaluationSandboxException(503, "Evaluation completion is not yet safe");
    }
    return new CompletionResult(dead.sandboxId(), "DEAD");
  }

  private static void verifyFixtureClosure(
      Sandbox sandbox, List<EvaluationResetRequest.ProductFixture> fixtures) {
    if (fixtures.size() != sandbox.fixtureCount()
        || !EvaluationRequestParser.fixtureDigest(fixtures).equals(sandbox.fixtureDigest())) {
      throw new EvaluationSandboxException(409, "Conflicting evaluation fixtures");
    }
  }

  private static ResetResult result(Sandbox sandbox) {
    if (!"ACTIVE".equals(sandbox.lifecycleState()) || sandbox.handle() == null) {
      throw new IllegalStateException("Reset result is not active");
    }
    return new ResetResult(sandbox.sandboxId(), sandbox.handle());
  }

  public record ResetResult(String sandboxId, String testUserHandle) {}

  public record CompletionResult(String sandboxId, String state) {}
}
