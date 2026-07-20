package io.citybuddy.commerce.evaluation;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public final class EvaluationLegacyAuditCommitmentStore {
  private EvaluationLegacyAuditCommitmentStore() {}

  public static Snapshot load(JdbcTemplate jdbc) {
    List<Watermark> watermarks =
        jdbc.query(
            """
            SELECT watermark_key, commitment_format, legacy_set_digest, cutoff_sequence_id,
                   cutoff_audit_reference_id,
                   CAST(UNIX_TIMESTAMP(cutoff_created_at) * 1000000 AS UNSIGNED)
                     AS cutoff_created_at_epoch_micros,
                   legacy_row_count, recorded_at IS NOT NULL AS recorded
            FROM eval_commerce_audit_legacy_watermark
            """,
            (result, row) ->
                new Watermark(
                    result.getString("watermark_key"),
                    result.getString("commitment_format"),
                    result.getString("legacy_set_digest"),
                    result.getLong("cutoff_sequence_id"),
                    result.getString("cutoff_audit_reference_id"),
                    instant(result.getObject("cutoff_created_at_epoch_micros", Long.class)),
                    result.getLong("legacy_row_count"),
                    result.getBoolean("recorded")));
    List<EvaluationLegacyAuditCommitment.Row> rows =
        jdbc.query(
            """
            SELECT sequence_id, audit_reference_id, sandbox_id, support_session_id, trace_id,
                   operation_id, entity_type, entity_id, entity_version, outcome,
                   created_at_anchor,
                   CAST(UNIX_TIMESTAMP(created_at) * 1000000 AS UNSIGNED)
                     AS created_at_epoch_micros
            FROM eval_commerce_audit_reference
            WHERE created_at_anchor = 'LEGACY_CUTOFF'
            ORDER BY sequence_id
            """,
            (result, row) ->
                new EvaluationLegacyAuditCommitment.Row(
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
                    instant(result.getLong("created_at_epoch_micros")),
                    result.getString("created_at_anchor")));
    return new Snapshot(watermarks, rows);
  }

  private static Instant instant(Long epochMicros) {
    return epochMicros == null ? null : instant(epochMicros.longValue());
  }

  private static Instant instant(long epochMicros) {
    return Instant.ofEpochSecond(
        Math.floorDiv(epochMicros, 1_000_000), Math.floorMod(epochMicros, 1_000_000) * 1_000);
  }

  public record Snapshot(
      List<Watermark> watermarks, List<EvaluationLegacyAuditCommitment.Row> rows) {
    public boolean isConsistent() {
      if (watermarks.size() != 1) {
        return false;
      }
      Watermark watermark = watermarks.getFirst();
      if (!"V013".equals(watermark.watermarkKey())
          || !EvaluationLegacyAuditCommitment.FORMAT.equals(watermark.commitmentFormat())
          || !watermark.recorded()
          || watermark.legacyRowCount() != rows.size()) {
        return false;
      }
      String currentDigest;
      try {
        currentDigest = EvaluationLegacyAuditCommitment.digest(rows);
      } catch (IllegalArgumentException exception) {
        return false;
      }
      if (!currentDigest.equals(watermark.legacySetDigest())) {
        return false;
      }
      if (watermark.cutoffSequenceId() == 0) {
        return rows.isEmpty()
            && watermark.cutoffAuditReferenceId() == null
            && watermark.cutoffCreatedAt() == null;
      }
      if (watermark.cutoffAuditReferenceId() == null || watermark.cutoffCreatedAt() == null) {
        return false;
      }
      boolean cutoffMember = false;
      for (EvaluationLegacyAuditCommitment.Row row : rows) {
        if (row.sequenceId() > watermark.cutoffSequenceId()
            || row.createdAt() == null
            || row.createdAt().isAfter(watermark.cutoffCreatedAt())) {
          return false;
        }
        if (row.sequenceId() == watermark.cutoffSequenceId()
            && row.auditReferenceId().equals(watermark.cutoffAuditReferenceId())) {
          cutoffMember = true;
        }
      }
      return cutoffMember;
    }

    public boolean contains(long sequenceId, String auditReferenceId) {
      return isConsistent()
          && rows.stream()
              .anyMatch(
                  row ->
                      row.sequenceId() == sequenceId
                          && row.auditReferenceId().equals(auditReferenceId));
    }
  }

  public record Watermark(
      String watermarkKey,
      String commitmentFormat,
      String legacySetDigest,
      long cutoffSequenceId,
      String cutoffAuditReferenceId,
      Instant cutoffCreatedAt,
      long legacyRowCount,
      boolean recorded) {}
}
