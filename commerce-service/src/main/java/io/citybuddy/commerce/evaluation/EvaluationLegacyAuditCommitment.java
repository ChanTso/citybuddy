package io.citybuddy.commerce.evaluation;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

public final class EvaluationLegacyAuditCommitment {
  public static final String FORMAT = "CITYBUDDY_EVAL_AUDIT_LEGACY_LPUTF8_SHA256_CHAIN_V1";

  private EvaluationLegacyAuditCommitment() {}

  public static String digest(List<Row> rows) {
    byte[] commitment = sha256(FORMAT.getBytes(StandardCharsets.UTF_8));
    long previousSequence = 0;
    for (Row row : rows) {
      if (row.sequenceId() <= previousSequence) {
        throw new IllegalArgumentException("Legacy audit rows must have increasing sequence ids");
      }
      previousSequence = row.sequenceId();
      byte[] rowDigest = sha256(canonicalRow(row));
      byte[] chained = new byte[commitment.length + rowDigest.length];
      System.arraycopy(commitment, 0, chained, 0, commitment.length);
      System.arraycopy(rowDigest, 0, chained, commitment.length, rowDigest.length);
      commitment = sha256(chained);
    }
    return HexFormat.of().formatHex(commitment);
  }

  private static byte[] canonicalRow(Row row) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    append(bytes, Long.toString(row.sequenceId()));
    append(bytes, row.auditReferenceId());
    append(bytes, row.sandboxId());
    append(bytes, row.supportSessionId());
    append(bytes, row.traceId());
    append(bytes, row.operationId());
    append(bytes, row.entityType());
    append(bytes, row.entityId());
    append(bytes, Long.toString(row.entityVersion()));
    append(bytes, row.outcome());
    append(bytes, row.createdAt() == null ? null : epochMicros(row.createdAt()));
    append(bytes, row.createdAtAnchor());
    return bytes.toByteArray();
  }

  private static void append(ByteArrayOutputStream target, String value) {
    if (value == null) {
      target.writeBytes("N;".getBytes(StandardCharsets.US_ASCII));
      return;
    }
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    target.write('V');
    target.writeBytes(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
    target.write(':');
    target.writeBytes(encoded);
    target.write(';');
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String epochMicros(Instant value) {
    return Long.toString(
        Math.addExact(
            Math.multiplyExact(value.getEpochSecond(), 1_000_000), value.getNano() / 1_000));
  }

  public record Row(
      long sequenceId,
      String auditReferenceId,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String entityType,
      String entityId,
      long entityVersion,
      String outcome,
      Instant createdAt,
      String createdAtAnchor) {}
}
