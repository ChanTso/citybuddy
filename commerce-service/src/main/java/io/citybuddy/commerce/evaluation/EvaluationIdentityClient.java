package io.citybuddy.commerce.evaluation;

import java.time.Instant;

public interface EvaluationIdentityClient {
  Provisioned provision(
      String sandboxId,
      String caseCorrelation,
      String testUserLabel,
      int ttlSeconds,
      String idempotencyKey);

  void revoke(String handle, String sandboxId, String caseCorrelation, String idempotencyKey);

  record Provisioned(String handle, Instant expiresAt) {}
}
