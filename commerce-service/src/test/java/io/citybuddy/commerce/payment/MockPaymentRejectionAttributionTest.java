package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class MockPaymentRejectionAttributionTest {

  @Test
  void directUserAuthorizationRejectionHasAUniqueInternalReason(CapturedOutput output) {
    MockPaymentExceptionHandler handler = new MockPaymentExceptionHandler();

    var response =
        handler.handle(
            new MockPaymentException(
                403,
                "AUTHORIZATION",
                MockPaymentRejectionReason.DIRECT_USER_AUTHORIZATION_REJECTED,
                "Direct-user payment authorization failed"));

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    assertThat(response.getBody())
        .containsExactlyEntriesOf(
            Map.of(
                "category", "AUTHORIZATION",
                "message", "Direct-user payment authorization failed"));
    assertThat(output).contains("reason_code=DIRECT_USER_AUTHORIZATION_REJECTED");
  }

  @Test
  void missingEvaluationComponentRejectionHasAUniqueInternalReason(CapturedOutput output) {
    MockPaymentExceptionHandler handler = new MockPaymentExceptionHandler();

    handler.handle(
        new MockPaymentException(
            403,
            "AUTHORIZATION",
            MockPaymentRejectionReason.EVALUATION_COMPONENT_UNAVAILABLE,
            "Evaluation payment is unavailable"));

    assertThat(output).contains("reason_code=EVALUATION_COMPONENT_UNAVAILABLE");
  }

  @Test
  void unattributedForbiddenConstructionIsRejected() {
    assertThatThrownBy(
            () -> new MockPaymentException(403, "AUTHORIZATION", "unattributed rejection"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MockPaymentException(
                    403,
                    "AUTHORIZATION",
                    MockPaymentRejectionReason.NOT_APPLICABLE,
                    "unattributed rejection"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
