package io.citybuddy.commerce.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class EvaluationAuditReferenceIdentity {
  private EvaluationAuditReferenceIdentity() {}

  public static String productFixture(
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String productId,
      long entityVersion) {
    return lengthPrefixedDigest(
        sandboxId,
        supportSessionId,
        traceId,
        operationId,
        EvaluationAuditEntityType.PRODUCT_FIXTURE.name(),
        productId,
        Long.toString(entityVersion),
        "OBSERVED");
  }

  public static String paymentCallback(
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String callbackEventId,
      long entityVersion) {
    return newlineDigest(
        sandboxId,
        supportSessionId,
        traceId,
        operationId,
        callbackEventId,
        Long.toString(entityVersion));
  }

  private static String lengthPrefixedDigest(String... values) {
    MessageDigest digest = sha256();
    for (String value : values) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
      digest.update((byte) ':');
      digest.update(bytes);
      digest.update((byte) ';');
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String newlineDigest(String... values) {
    return HexFormat.of()
        .formatHex(sha256().digest(String.join("\n", values).getBytes(StandardCharsets.UTF_8)));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
