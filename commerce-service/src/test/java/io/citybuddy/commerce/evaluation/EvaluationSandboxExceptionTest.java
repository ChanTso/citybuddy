package io.citybuddy.commerce.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class EvaluationSandboxExceptionTest {
  @Test
  void requiresEveryForbiddenDecisionToCarryAnAttributionReason() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new EvaluationSandboxException(403, "unattributed"));

    EvaluationSandboxException attributed =
        new EvaluationSandboxException(
            403,
            EvaluationRejectionReason.ACCESS_SANDBOX_NOT_ACTIVE,
            "Evaluation sandbox is inactive");

    assertThat(attributed.status()).isEqualTo(403);
    assertThat(attributed.reason()).isEqualTo(EvaluationRejectionReason.ACCESS_SANDBOX_NOT_ACTIVE);
  }
}
