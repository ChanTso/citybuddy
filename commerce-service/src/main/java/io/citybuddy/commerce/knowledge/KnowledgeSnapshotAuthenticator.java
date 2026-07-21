package io.citybuddy.commerce.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class KnowledgeSnapshotAuthenticator {
  private final byte[] expectedClient;
  private final byte[] expectedSecret;

  public KnowledgeSnapshotAuthenticator(KnowledgeSnapshotProperties properties) {
    expectedClient = properties.clientId().getBytes(StandardCharsets.UTF_8);
    expectedSecret = properties.clientSecret().getBytes(StandardCharsets.UTF_8);
  }

  public void authenticate(String authorization) {
    try {
      if (authorization == null || !authorization.startsWith("Basic ")) {
        throw new IllegalArgumentException();
      }
      String decoded =
          new String(
              Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
      int separator = decoded.indexOf(':');
      if (separator <= 0 || separator == decoded.length() - 1) {
        throw new IllegalArgumentException();
      }
      byte[] client = decoded.substring(0, separator).getBytes(StandardCharsets.UTF_8);
      byte[] secret = decoded.substring(separator + 1).getBytes(StandardCharsets.UTF_8);
      if (!MessageDigest.isEqual(expectedClient, client)
          | !MessageDigest.isEqual(expectedSecret, secret)) {
        throw new IllegalArgumentException();
      }
    } catch (IllegalArgumentException exception) {
      throw new KnowledgeSnapshotException(401, "invalid_snapshot_credential");
    }
  }
}
