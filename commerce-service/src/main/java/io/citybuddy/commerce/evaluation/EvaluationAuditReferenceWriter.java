package io.citybuddy.commerce.evaluation;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

public final class EvaluationAuditReferenceWriter {
  private static final Set<EvaluationAuditEntityType> SUPPORTED_TYPES =
      Collections.unmodifiableSet(EnumSet.allOf(EvaluationAuditEntityType.class));

  private EvaluationAuditReferenceWriter() {}

  public static Instant monotonicCreatedAt(JdbcTemplate jdbc, String sandboxId, Instant candidate) {
    Instant normalized = candidate.truncatedTo(ChronoUnit.MICROS);
    List<Instant> latest =
        jdbc.query(
            """
            SELECT created_at
            FROM eval_commerce_audit_reference
            WHERE sandbox_id = ?
            ORDER BY sequence_id DESC
            LIMIT 1
            FOR SHARE
            """,
            (result, row) -> result.getTimestamp("created_at").toInstant(),
            sandboxId);
    if (latest.isEmpty() || !latest.getFirst().isAfter(normalized)) {
      return normalized;
    }
    // Every audit producer holds the sandbox row lock, so this clamp makes sequence/time order
    // a persisted invariant even if the wall clock moves backwards.
    return latest.getFirst();
  }

  public static void insert(
      JdbcTemplate jdbc,
      String auditReferenceId,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      EvaluationAuditEntityType entityType,
      String entityId,
      long entityVersion,
      Instant createdAt) {
    jdbc.update(
        """
        INSERT INTO eval_commerce_audit_reference
          (audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id,
           entity_type, entity_id, entity_version, outcome, created_at, created_at_anchor)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OBSERVED', ?, 'BUSINESS_EVENT')
        """,
        auditReferenceId,
        sandboxId,
        supportSessionId,
        traceId,
        operationId,
        entityType.name(),
        entityId,
        entityVersion,
        Timestamp.from(createdAt));
  }

  public static Set<EvaluationAuditEntityType> supportedTypes() {
    return SUPPORTED_TYPES;
  }
}
