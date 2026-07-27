package io.citybuddy.commerce.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical immutable commitment shared by standard-order creation and durable replay validation.
 */
public final class StandardOrderIntentCommitment {
  public static final String CANONICALIZER_ID = "STANDARD_ORDER_INTENT_V1";

  private StandardOrderIntentCommitment() {}

  public static String hash(String productId, int quantity, long expectedProductVersion) {
    String normalizedProductId = productId.strip();
    String canonical =
        normalizedProductId.length()
            + ":"
            + normalizedProductId
            + ":"
            + quantity
            + ":"
            + expectedProductVersion;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
