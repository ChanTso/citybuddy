package io.citybuddy.auth.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;

final class ServiceCredentialVerifier {
  static final String DIGEST_PREFIX = "sha256$v1$";
  private static final String GENERATED_SECRET_PREFIX = "cbsvc_v1_";
  private static final Pattern GENERATED_SECRET =
      Pattern.compile("^" + GENERATED_SECRET_PREFIX + "[0-9a-f]{64}$");
  private static final Pattern LEGACY_BCRYPT = Pattern.compile("^\\$2[aby]\\$.*$");
  private static final byte[] DOMAIN =
      "citybuddy-service-credential-v1".getBytes(StandardCharsets.UTF_8);

  private final PasswordEncoder legacyPasswordEncoder;

  ServiceCredentialVerifier(PasswordEncoder legacyPasswordEncoder) {
    this.legacyPasswordEncoder = legacyPasswordEncoder;
  }

  boolean matches(String clientId, String presentedSecret, String persistedHash) {
    if (clientId == null || presentedSecret == null || persistedHash == null) {
      return false;
    }
    if (persistedHash.startsWith("sha256$")) {
      return matchesDigest(clientId, presentedSecret, persistedHash);
    }
    return LEGACY_BCRYPT.matcher(persistedHash).matches()
        && legacyPasswordEncoder.matches(presentedSecret, persistedHash);
  }

  private static boolean matchesDigest(
      String clientId, String presentedSecret, String persistedHash) {
    if (!GENERATED_SECRET.matcher(presentedSecret).matches()
        || !persistedHash.startsWith(DIGEST_PREFIX)
        || persistedHash.length() != DIGEST_PREFIX.length() + 64) {
      return false;
    }
    try {
      byte[] expected = HexFormat.of().parseHex(persistedHash.substring(DIGEST_PREFIX.length()));
      return MessageDigest.isEqual(expected, digest(clientId, presentedSecret));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  static String encodedDigest(String clientId, String generatedSecret) {
    if (clientId == null
        || clientId.isBlank()
        || !GENERATED_SECRET.matcher(generatedSecret == null ? "" : generatedSecret).matches()) {
      throw new IllegalArgumentException(
          "A client id and generated service credential are required");
    }
    return DIGEST_PREFIX + HexFormat.of().formatHex(digest(clientId, generatedSecret));
  }

  private static byte[] digest(String clientId, String presentedSecret) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      update(digest, DOMAIN);
      update(digest, clientId.getBytes(StandardCharsets.UTF_8));
      update(digest, presentedSecret.getBytes(StandardCharsets.UTF_8));
      return digest.digest();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void update(MessageDigest digest, byte[] value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    digest.update(value);
  }
}
