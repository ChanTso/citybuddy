package io.citybuddy.commerce.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class MockPaymentCallbackAuthenticator {
  private static final HexFormat HEX = HexFormat.of();

  private final MockPaymentProperties properties;
  private final Clock clock;

  public MockPaymentCallbackAuthenticator(MockPaymentProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public void authenticate(
      String keyId,
      String timestamp,
      String signature,
      String idempotencyKey,
      MockPaymentCallbackRequest request) {
    require(properties.callbackKeyId().equals(keyId));
    require(timestamp != null && timestamp.matches("[0-9]{1,19}"));
    require(signature != null && signature.matches("[0-9a-f]{64}"));
    require(idempotencyKey != null && idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}"));
    Instant signedAt;
    try {
      signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
    } catch (RuntimeException exception) {
      throw unauthorized();
    }
    Instant now = clock.instant();
    require(!signedAt.isAfter(now.plus(properties.callbackClockSkew())));
    require(
        !signedAt.isBefore(
            now.minus(properties.callbackMaximumAge()).minus(properties.callbackClockSkew())));
    byte[] expected =
        hmac(
            properties.callbackSecret(),
            canonical(keyId, timestamp, idempotencyKey, requireRequest(request)));
    byte[] supplied;
    try {
      supplied = HEX.parseHex(signature);
    } catch (IllegalArgumentException exception) {
      throw unauthorized();
    }
    require(MessageDigest.isEqual(expected, supplied));
  }

  static String canonical(
      String keyId, String timestamp, String idempotencyKey, MockPaymentCallbackRequest request) {
    return String.join(
        "\n",
        keyId,
        timestamp,
        idempotencyKey,
        request.callbackEventId(),
        request.callbackCorrelationId(),
        request.orderId(),
        Long.toString(request.amountMinor()),
        request.currency(),
        request.outcome(),
        nullable(request.sandboxId()),
        nullable(request.supportSessionId()),
        nullable(request.traceId()),
        nullable(request.operationId()));
  }

  static byte[] hmac(String secret, String canonical) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (Exception exception) {
      throw new IllegalStateException("Callback signature algorithm is unavailable", exception);
    }
  }

  private static MockPaymentCallbackRequest requireRequest(MockPaymentCallbackRequest request) {
    if (request == null
        || request.callbackEventId() == null
        || request.callbackCorrelationId() == null
        || request.orderId() == null
        || request.amountMinor() == null
        || request.currency() == null
        || request.outcome() == null) {
      throw unauthorized();
    }
    return request;
  }

  private static String nullable(String value) {
    return value == null ? "" : value;
  }

  private static void require(boolean condition) {
    if (!condition) {
      throw unauthorized();
    }
  }

  private static MockPaymentException unauthorized() {
    return new MockPaymentException(
        401, "AUTHENTICATION", "Mock-payment callback authentication failed");
  }
}
