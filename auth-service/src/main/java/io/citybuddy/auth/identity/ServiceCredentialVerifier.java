package io.citybuddy.auth.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.password.PasswordEncoder;

final class ServiceCredentialVerifier {
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final byte[] DOMAIN =
      "citybuddy-service-credential-cache-v1".getBytes(StandardCharsets.UTF_8);

  private final PasswordEncoder passwordEncoder;
  private final SecretKeySpec fingerprintKey;
  private final Map<String, CredentialProof> proofs = new ConcurrentHashMap<>();
  private final Map<VerificationKey, CompletableFuture<Boolean>> inFlight =
      new ConcurrentHashMap<>();

  ServiceCredentialVerifier(PasswordEncoder passwordEncoder) {
    this(passwordEncoder, randomKey());
  }

  ServiceCredentialVerifier(PasswordEncoder passwordEncoder, byte[] fingerprintKey) {
    this.passwordEncoder = passwordEncoder;
    this.fingerprintKey = new SecretKeySpec(fingerprintKey.clone(), HMAC_ALGORITHM);
  }

  boolean matches(String clientId, String presentedSecret, String persistedHash) {
    if (clientId == null || presentedSecret == null || persistedHash == null) {
      return false;
    }

    byte[] fingerprint = fingerprint(clientId, presentedSecret, persistedHash);
    CredentialProof cached = proofs.get(clientId);
    if (matches(cached, persistedHash, fingerprint)) {
      return true;
    }
    VerificationKey verificationKey =
        new VerificationKey(
            clientId, persistedHash, Base64.getEncoder().encodeToString(fingerprint));
    CompletableFuture<Boolean> verification = new CompletableFuture<>();
    CompletableFuture<Boolean> existing = inFlight.putIfAbsent(verificationKey, verification);
    if (existing != null) {
      return await(existing);
    }
    try {
      cached = proofs.get(clientId);
      if (matches(cached, persistedHash, fingerprint)) {
        verification.complete(true);
        return true;
      }
      boolean valid = passwordEncoder.matches(presentedSecret, persistedHash);
      if (valid) {
        proofs.put(clientId, new CredentialProof(persistedHash, fingerprint));
      }
      verification.complete(valid);
      return valid;
    } catch (RuntimeException | Error exception) {
      verification.completeExceptionally(exception);
      throw exception;
    } finally {
      inFlight.remove(verificationKey, verification);
    }
  }

  private static boolean await(CompletableFuture<Boolean> verification) {
    try {
      return verification.join();
    } catch (CompletionException exception) {
      if (exception.getCause() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (exception.getCause() instanceof Error error) {
        throw error;
      }
      throw exception;
    }
  }

  private static boolean matches(CredentialProof proof, String persistedHash, byte[] fingerprint) {
    return proof != null
        && proof.persistedHash().equals(persistedHash)
        && MessageDigest.isEqual(proof.fingerprint(), fingerprint);
  }

  private byte[] fingerprint(String clientId, String presentedSecret, String persistedHash) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(fingerprintKey);
      update(mac, DOMAIN);
      update(mac, clientId.getBytes(StandardCharsets.UTF_8));
      update(mac, persistedHash.getBytes(StandardCharsets.UTF_8));
      update(mac, presentedSecret.getBytes(StandardCharsets.UTF_8));
      return mac.doFinal();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
    }
  }

  private static void update(Mac mac, byte[] value) {
    mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    mac.update(value);
  }

  private static byte[] randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return key;
  }

  private record CredentialProof(String persistedHash, byte[] fingerprint) {
    private CredentialProof {
      fingerprint = fingerprint.clone();
    }

    @Override
    public byte[] fingerprint() {
      return fingerprint.clone();
    }
  }

  private record VerificationKey(String clientId, String persistedHash, String fingerprintBase64) {}
}
