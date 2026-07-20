package io.citybuddy.commerce.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationAuditTypeCoverageTest {
  @Test
  void everyWritableAuditTypeHasAnExplicitReconciliationBranch() {
    assertThat(EvaluationAuditReferenceWriter.supportedTypes())
        .containsExactlyInAnyOrderElementsOf(EvaluationViewRepository.reconciledAuditTypes());
  }

  @Test
  void migratedProductIdentityUsesTheLandedLengthPrefixedDigest() {
    assertThat(
            EvaluationAuditReferenceIdentity.productFixture(
                "sandbox-legacy-upgrade",
                "legacy-upgrade-session",
                "legacy-upgrade-trace",
                "1".repeat(64),
                "legacy-product",
                1))
        .isEqualTo("de6e6aa68bce6a01607673c4c921287c49b63720ca9fc963c6932b03f10cb31b");
  }

  @Test
  void legacyCommitmentUsesThePinnedLengthPrefixedChainFormat() {
    EvaluationLegacyAuditCommitment.Row row =
        new EvaluationLegacyAuditCommitment.Row(
            1,
            "a".repeat(64),
            "sandbox-legacy-upgrade",
            "legacy-upgrade-session",
            "legacy-upgrade-trace",
            "1".repeat(64),
            "PRODUCT_FIXTURE",
            "legacy-product",
            1,
            "OBSERVED",
            Instant.parse("2026-07-20T01:02:03.123456Z"),
            "LEGACY_CUTOFF");

    assertThat(EvaluationLegacyAuditCommitment.digest(List.of()))
        .isEqualTo("1947f68d305b5013a6d2b96f18beb29e9b46ba697b276387cac464cd048aada4");
    assertThat(EvaluationLegacyAuditCommitment.digest(List.of(row)))
        .isEqualTo("749fe7471ce875d4dfbac2a5902e69ebf54ba9177f70ef5bc7ce32c825dd32cd");
    assertThat(
            EvaluationLegacyAuditCommitment.digest(
                List.of(
                    new EvaluationLegacyAuditCommitment.Row(
                        row.sequenceId(),
                        row.auditReferenceId(),
                        "different-sandbox",
                        row.supportSessionId(),
                        row.traceId(),
                        row.operationId(),
                        row.entityType(),
                        row.entityId(),
                        row.entityVersion(),
                        row.outcome(),
                        row.createdAt(),
                        row.createdAtAnchor()))))
        .isNotEqualTo(EvaluationLegacyAuditCommitment.digest(List.of(row)));
  }
}
