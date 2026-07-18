package io.citybuddy.commerce.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class EvaluationManagementAuthenticator {
  private final byte[] expectedClient;
  private final byte[] expectedSecret;

  public EvaluationManagementAuthenticator(EvaluationSandboxProperties properties) {
    expectedClient = properties.managementClientId().getBytes(StandardCharsets.UTF_8);
    expectedSecret = properties.managementClientSecret().getBytes(StandardCharsets.UTF_8);
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
      throw new EvaluationSandboxException(401, "Invalid evaluation credential");
    }
  }
}
