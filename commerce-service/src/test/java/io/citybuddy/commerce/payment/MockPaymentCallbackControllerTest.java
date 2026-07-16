package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MockPaymentCallbackControllerTest {
  @Test
  void failedAuthenticationCannotReachBusinessService() {
    MockPaymentService service = mock(MockPaymentService.class);
    MockPaymentCallbackAuthenticator authenticator =
        new MockPaymentCallbackAuthenticator(
            new MockPaymentProperties(
                "payment:create",
                "callback-key",
                "not-a-secret-not-a-secret-not-a-secret",
                Duration.ofMinutes(5),
                Duration.ofSeconds(30)),
            Clock.fixed(Instant.parse("2026-07-16T01:00:00Z"), ZoneOffset.UTC));
    MockPaymentCallbackController controller =
        new MockPaymentCallbackController(authenticator, service);
    MockPaymentCallbackRequest request =
        new MockPaymentCallbackRequest(
            "00000000-0000-0000-0000-000000000070",
            "00000000-0000-0000-0000-000000000071",
            "00000000-0000-0000-0000-000000000072",
            100L,
            "AUD",
            "SUCCEEDED");

    assertThatThrownBy(
            () ->
                controller.callback(
                    "callback-key", "1784163600", "0".repeat(64), "callback-key", request))
        .isInstanceOf(MockPaymentException.class);
    verifyNoInteractions(service);
  }
}
