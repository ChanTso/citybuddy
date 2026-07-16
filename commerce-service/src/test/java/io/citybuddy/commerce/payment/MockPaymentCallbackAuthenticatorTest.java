package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class MockPaymentCallbackAuthenticatorTest {
  private static final Instant NOW = Instant.parse("2026-07-16T01:00:00Z");
  private static final String KEY_ID = "callback-key";
  private static final String SECRET = "not-a-secret-not-a-secret-not-a-secret";
  private static final MockPaymentCallbackRequest REQUEST =
      new MockPaymentCallbackRequest(
          "00000000-0000-0000-0000-000000000070",
          "00000000-0000-0000-0000-000000000071",
          "00000000-0000-0000-0000-000000000072",
          1200L,
          "AUD",
          "SUCCEEDED");

  private final MockPaymentCallbackAuthenticator authenticator =
      new MockPaymentCallbackAuthenticator(
          new MockPaymentProperties(
              "payment:create", KEY_ID, SECRET, Duration.ofMinutes(5), Duration.ofSeconds(30)),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void acceptsFreshAndSkewBoundSignatures() {
    assertAccepted(NOW.minus(Duration.ofMinutes(5).plusSeconds(30)), "callback-old-bound");
    assertAccepted(NOW.plusSeconds(30), "callback-future-bound");
  }

  @Test
  void rejectsMissingIdentityStaleFutureAndInvalidSignatures() {
    assertUnauthorized(
        () ->
            authenticator.authenticate(KEY_ID, epoch(NOW), signature(NOW, "valid"), null, REQUEST));
    assertUnauthorized(() -> authenticate(NOW.minusSeconds(331), "stale"));
    assertUnauthorized(() -> authenticate(NOW.plusSeconds(31), "future"));
    assertUnauthorized(
        () ->
            authenticator.authenticate(
                KEY_ID, epoch(NOW), "0".repeat(64), "invalid-signature", REQUEST));
    assertUnauthorized(
        () ->
            authenticator.authenticate(
                "wrong-key", epoch(NOW), signature(NOW, "wrong-key"), "wrong-key", REQUEST));
  }

  private void assertAccepted(Instant signedAt, String idempotencyKey) {
    assertThatCode(() -> authenticate(signedAt, idempotencyKey)).doesNotThrowAnyException();
  }

  private void authenticate(Instant signedAt, String idempotencyKey) {
    authenticator.authenticate(
        KEY_ID, epoch(signedAt), signature(signedAt, idempotencyKey), idempotencyKey, REQUEST);
  }

  private String signature(Instant signedAt, String idempotencyKey) {
    String canonical =
        MockPaymentCallbackAuthenticator.canonical(
            KEY_ID, epoch(signedAt), idempotencyKey, REQUEST);
    return HexFormat.of().formatHex(MockPaymentCallbackAuthenticator.hmac(SECRET, canonical));
  }

  private static String epoch(Instant instant) {
    return Long.toString(instant.getEpochSecond());
  }

  private static void assertUnauthorized(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
    assertThatThrownBy(work)
        .isInstanceOfSatisfying(
            MockPaymentException.class,
            exception -> {
              org.assertj.core.api.Assertions.assertThat(exception.status()).isEqualTo(401);
              org.assertj.core.api.Assertions.assertThat(exception.category())
                  .isEqualTo("AUTHENTICATION");
            });
  }
}
