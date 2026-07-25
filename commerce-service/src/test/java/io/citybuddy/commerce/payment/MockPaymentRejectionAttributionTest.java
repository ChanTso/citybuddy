package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.citybuddy.commerce.evaluation.EvaluationRejectionReason;
import io.citybuddy.commerce.evaluation.EvaluationSandboxException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

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
  void durableIntegrityReasonStaysServerOnly(CapturedOutput output) {
    MockPaymentExceptionHandler handler = new MockPaymentExceptionHandler();

    var response =
        handler.handle(
            new MockPaymentException(
                409,
                "CONFLICT",
                MockPaymentRejectionReason.COMMITTED_PAYMENT_TRUTH_INCONSISTENT,
                "Committed payment truth is inconsistent"));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody())
        .containsExactlyEntriesOf(
            Map.of(
                "category", "CONFLICT",
                "message", "Committed payment truth is inconsistent"))
        .doesNotContainKey("reason");
    assertThat(output).contains("reason_code=COMMITTED_PAYMENT_TRUTH_INCONSISTENT");
  }

  @Test
  void sandboxHandlerPreservesExactRootCauseClasses(CapturedOutput output) {
    MockPaymentExceptionHandler handler = new MockPaymentExceptionHandler();

    var inactive =
        handler.handleSandbox(
            new EvaluationSandboxException(
                403,
                EvaluationRejectionReason.PAYMENT_SANDBOX_NOT_ACTIVE,
                "private inactive detail"));
    assertThat(inactive.getStatusCode().value()).isEqualTo(403);
    assertThat(inactive.getBody())
        .containsExactlyEntriesOf(
            Map.of("category", "AUTHORIZATION", "message", "Evaluation payment is unavailable"));

    var damaged =
        handler.handleSandbox(new EvaluationSandboxException(409, "private integrity detail"));
    assertThat(damaged.getStatusCode().value()).isEqualTo(409);
    assertThat(damaged.getBody())
        .containsExactlyEntriesOf(
            Map.of("category", "CONFLICT", "message", "Committed payment truth is inconsistent"));

    var indeterminate =
        handler.handleSandbox(new EvaluationSandboxException(503, "private dependency detail"));
    assertThat(indeterminate.getStatusCode().value()).isEqualTo(503);
    assertThat(indeterminate.getBody())
        .containsExactlyEntriesOf(
            Map.of("category", "UNAVAILABLE", "message", "Payment service is unavailable"));

    assertThat(output)
        .contains("reason_code=PAYMENT_SANDBOX_NOT_ACTIVE")
        .contains("reason_code=SANDBOX_NOT_ACTIVE")
        .contains("reason_code=COMMITTED_PAYMENT_TRUTH_INCONSISTENT")
        .contains("reason_code=DEPENDENCY_OBSERVATION_INDETERMINATE")
        .doesNotContain("private inactive detail")
        .doesNotContain("private integrity detail")
        .doesNotContain("private dependency detail");
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

  @Test
  void onlyResourceAvailabilityFailuresMapToUnavailable(CapturedOutput output) {
    MockPaymentExceptionHandler handler = new MockPaymentExceptionHandler();

    var response =
        handler.handleUnavailable(new DataAccessResourceFailureException("controlled outage"));
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(
            handler
                .handleUnavailable(
                    new CannotCreateTransactionException("controlled connection outage"))
                .getStatusCode()
                .value())
        .isEqualTo(503);

    ExceptionHandlerMethodResolver mappings =
        new ExceptionHandlerMethodResolver(MockPaymentExceptionHandler.class);
    assertThat(mappings.resolveMethod(new DataAccessResourceFailureException("controlled")))
        .isNotNull();
    assertThat(mappings.resolveMethod(new CannotCreateTransactionException("controlled")))
        .isNotNull();
    assertThat(mappings.resolveMethod(new CannotAcquireLockException("controlled"))).isNull();
    assertThat(mappings.resolveMethod(new DuplicateKeyException("controlled"))).isNull();
    assertThat(output).contains("reason_code=DEPENDENCY_OBSERVATION_INDETERMINATE");
  }

  @Test
  void lockCompetitionUsesItsOwnBoundedServerAttribution(CapturedOutput output) {
    MockPaymentExceptionHandler handler = new MockPaymentExceptionHandler();

    var response =
        handler.handle(
            new MockPaymentException(
                429,
                "INDETERMINATE",
                MockPaymentRejectionReason.PAYMENT_CONCURRENCY_OBSERVATION_INDETERMINATE,
                "Payment truth is indeterminate; retry the same request"));

    assertThat(response.getStatusCode().value()).isEqualTo(429);
    assertThat(response.getBody())
        .containsExactlyEntriesOf(
            Map.of(
                "category",
                "INDETERMINATE",
                "message",
                "Payment truth is indeterminate; retry the same request"))
        .doesNotContainKey("reason");
    assertThat(output)
        .contains("reason_code=PAYMENT_CONCURRENCY_OBSERVATION_INDETERMINATE")
        .doesNotContain("1205")
        .doesNotContain("1213")
        .doesNotContain("innodb");
  }

  @Test
  void exceptionProducerInventoryIsClosedAndRejectsMismatchedPublicClassification() {
    assertThat(Set.of(MockPaymentRejectionReason.values()))
        .containsExactlyInAnyOrder(
            MockPaymentRejectionReason.NOT_APPLICABLE,
            MockPaymentRejectionReason.DIRECT_USER_AUTHORIZATION_REJECTED,
            MockPaymentRejectionReason.EVALUATION_COMPONENT_UNAVAILABLE,
            MockPaymentRejectionReason.COMMITTED_PAYMENT_TRUTH_INCONSISTENT,
            MockPaymentRejectionReason.IDEMPOTENCY_INTENT_CONFLICT,
            MockPaymentRejectionReason.ORDER_NOT_ELIGIBLE,
            MockPaymentRejectionReason.CONCEALED_NOT_FOUND,
            MockPaymentRejectionReason.CALLBACK_TRUTH_NOT_FOUND,
            MockPaymentRejectionReason.SANDBOX_NOT_ACTIVE,
            MockPaymentRejectionReason.PAYMENT_CONCURRENCY_OBSERVATION_INDETERMINATE,
            MockPaymentRejectionReason.DEPENDENCY_OBSERVATION_INDETERMINATE);
    assertThat(
            java.util.Arrays.stream(MockPaymentRejectionReason.values())
                .filter(reason -> reason != MockPaymentRejectionReason.NOT_APPLICABLE)
                .map(MockPaymentRejectionReason::producer))
        .doesNotHaveDuplicates()
        .doesNotContain("");
    assertThatThrownBy(
            () ->
                new MockPaymentException(
                    503,
                    "UNAVAILABLE",
                    MockPaymentRejectionReason.PAYMENT_CONCURRENCY_OBSERVATION_INDETERMINATE,
                    "mismatched"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("attribution");
  }
}
