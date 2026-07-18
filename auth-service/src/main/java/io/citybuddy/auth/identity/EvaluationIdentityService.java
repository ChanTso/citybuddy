package io.citybuddy.auth.identity;

import io.citybuddy.auth.identity.AuthRepository.EvaluationPrincipal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;

public class EvaluationIdentityService {
  private static final Pattern BOUNDED_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]*$");
  private static final Pattern OPAQUE_HANDLE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
  private static final List<String> TEST_PERMISSIONS =
      List.of("support:session:create", "support:chat");

  private final AuthRepository repository;
  private final AuthKeySet keys;
  private final IdentityProperties properties;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public EvaluationIdentityService(
      AuthRepository repository, AuthKeySet keys, IdentityProperties properties, Clock clock) {
    this.repository = repository;
    this.keys = keys;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public ProvisionedPrincipal provision(
      String sandboxId,
      String caseCorrelation,
      String testUserLabel,
      int ttlSeconds,
      String idempotencyKey) {
    requireBounded(sandboxId, 64, "Invalid sandbox");
    requireBounded(caseCorrelation, 128, "Invalid case correlation");
    requireBounded(testUserLabel, 128, "Invalid test user label");
    requireBounded(idempotencyKey, 128, "Invalid idempotency key");
    if (ttlSeconds < 60 || ttlSeconds > 3600) {
      throw new IdentityException(400, "Invalid evaluation TTL");
    }

    Instant now = clock.instant();
    EvaluationPrincipal existing =
        repository.findEvaluationByProvisionKey(idempotencyKey).orElse(null);
    if (existing != null) {
      return sameProvisioning(existing, sandboxId, caseCorrelation, testUserLabel, ttlSeconds, now);
    }
    EvaluationPrincipal bound =
        repository.findEvaluationBySandboxCase(sandboxId, caseCorrelation).orElse(null);
    if (bound != null) {
      throw new IdentityException(409, "Conflicting evaluation provisioning");
    }

    EvaluationPrincipal candidate =
        new EvaluationPrincipal(
            UUID.randomUUID().toString(),
            opaqueHandle(),
            "eval-" + UUID.randomUUID(),
            sandboxId,
            caseCorrelation,
            testUserLabel,
            String.join(" ", TEST_PERMISSIONS),
            idempotencyKey,
            ttlSeconds,
            "PROVISIONED",
            now.plusSeconds(ttlSeconds),
            null,
            null);
    repository.insertEvaluationPrincipal(candidate);

    EvaluationPrincipal persisted =
        repository.findEvaluationByProvisionKeyForShare(idempotencyKey).orElse(null);
    if (persisted == null) {
      if (repository.findEvaluationBySandboxCaseForShare(sandboxId, caseCorrelation).isPresent()) {
        throw new IdentityException(409, "Conflicting evaluation provisioning");
      }
      throw new IllegalStateException("Evaluation provisioning did not persist");
    }
    return sameProvisioning(persisted, sandboxId, caseCorrelation, testUserLabel, ttlSeconds, now);
  }

  @Transactional
  public RevokedPrincipal revoke(
      String handle, String sandboxId, String caseCorrelation, String idempotencyKey) {
    requireOpaqueHandle(handle);
    requireBounded(sandboxId, 64, "Invalid sandbox");
    requireBounded(caseCorrelation, 128, "Invalid case correlation");
    requireBounded(idempotencyKey, 128, "Invalid idempotency key");
    EvaluationPrincipal existing =
        repository
            .findEvaluationByHandleForUpdate(handle)
            .orElseThrow(() -> new IdentityException(404, "Evaluation principal not found"));
    requireBinding(existing, sandboxId, caseCorrelation);
    if ("REVOKED".equals(existing.state())) {
      if (!idempotencyKey.equals(existing.revokeIdempotencyKey())) {
        throw new IdentityException(409, "Conflicting evaluation revocation");
      }
      return new RevokedPrincipal(existing.opaqueHandle(), "REVOKED");
    }
    int changed =
        repository.revokeEvaluationPrincipal(
            handle, sandboxId, caseCorrelation, idempotencyKey, clock.instant());
    if (changed != 1) {
      EvaluationPrincipal converged =
          repository
              .findEvaluationByHandleForUpdate(handle)
              .orElseThrow(() -> new IdentityException(404, "Evaluation principal not found"));
      if (!"REVOKED".equals(converged.state())
          || !idempotencyKey.equals(converged.revokeIdempotencyKey())) {
        throw new IdentityException(409, "Conflicting evaluation revocation");
      }
    }
    return new RevokedPrincipal(handle, "REVOKED");
  }

  @Transactional(readOnly = true)
  public AuthController.TokenResponse issueToken(String handle, String sandboxId) {
    requireOpaqueHandle(handle);
    requireBounded(sandboxId, 64, "Invalid sandbox");
    Instant now = clock.instant();
    EvaluationPrincipal principal =
        repository
            .findEvaluationByHandle(handle)
            .filter(item -> item.sandboxId().equals(sandboxId))
            .filter(item -> "PROVISIONED".equals(item.state()))
            .filter(item -> item.expiresAt().isAfter(now))
            .orElseThrow(() -> new IdentityException(401, "Invalid evaluation handle"));
    Instant tokenExpiry =
        principal.expiresAt().isBefore(now.plus(properties.directTtl()))
            ? principal.expiresAt()
            : now.plus(properties.directTtl());
    boolean currentSigningKey =
        repository.publicKeyMetadata().stream()
            .anyMatch(
                metadata ->
                    properties.currentKid().equals(metadata.kid())
                        && "CURRENT".equals(metadata.state()));
    if (!currentSigningKey) {
      throw new IllegalStateException("Current signing key is not published");
    }
    String token =
        keys.evaluationDirectToken(
            principal.subject(), principal.permissionList(), sandboxId, tokenExpiry);
    return new AuthController.TokenResponse(
        token, "Bearer", Math.max(1, tokenExpiry.getEpochSecond() - now.getEpochSecond()));
  }

  private ProvisionedPrincipal sameProvisioning(
      EvaluationPrincipal principal,
      String sandboxId,
      String caseCorrelation,
      String testUserLabel,
      int ttlSeconds,
      Instant now) {
    if (!principal.sandboxId().equals(sandboxId)
        || !principal.caseCorrelation().equals(caseCorrelation)
        || !principal.testUserLabel().equals(testUserLabel)
        || principal.ttlSeconds() != ttlSeconds
        || !"PROVISIONED".equals(principal.state())
        || !principal.expiresAt().isAfter(now)) {
      throw new IdentityException(409, "Conflicting evaluation provisioning");
    }
    return new ProvisionedPrincipal(principal.opaqueHandle(), principal.expiresAt());
  }

  private static void requireBinding(
      EvaluationPrincipal principal, String sandboxId, String caseCorrelation) {
    if (!principal.sandboxId().equals(sandboxId)
        || !principal.caseCorrelation().equals(caseCorrelation)) {
      throw new IdentityException(404, "Evaluation principal not found");
    }
  }

  private static void requireBounded(String value, int maximum, String message) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || !BOUNDED_ID.matcher(value).matches()) {
      throw new IdentityException(400, message);
    }
  }

  private static void requireOpaqueHandle(String handle) {
    if (handle == null || !OPAQUE_HANDLE.matcher(handle).matches()) {
      throw new IdentityException(400, "Invalid evaluation handle");
    }
  }

  private String opaqueHandle() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public record ProvisionedPrincipal(String handle, Instant expiresAt) {}

  public record RevokedPrincipal(String handle, String state) {}
}
