package io.citybuddy.commerce.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.citybuddy.commerce.identity.IdentityVerificationUnavailableException;
import io.citybuddy.commerce.mysql.MySqlSessionPolicyRestorationException;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(OutputCaptureExtension.class)
class RefundRejectionAttributionTest {
  @Test
  void indeterminateAndIntegrityReasonsStayServerOnly(CapturedOutput output) {
    RefundExceptionHandler handler = new RefundExceptionHandler();

    var indeterminate =
        handler.handle(
            new RefundException(
                429,
                "INDETERMINATE",
                RefundRejectionReason.REFUND_CONCURRENCY_OBSERVATION_INDETERMINATE,
                "Refund truth is indeterminate; retry the same request"));
    var damaged =
        handler.handle(
            new RefundException(
                409,
                "CONFLICT",
                RefundRejectionReason.REFUND_DURABLE_TRUTH_INCONSISTENT,
                "Refund durable truth is inconsistent"));

    assertThat(indeterminate.getStatusCode().value()).isEqualTo(429);
    assertThat(indeterminate.getBody())
        .containsExactlyEntriesOf(
            Map.of(
                "category",
                "INDETERMINATE",
                "message",
                "Refund truth is indeterminate; retry the same request"))
        .doesNotContainKey("reason");
    assertThat(damaged.getStatusCode().value()).isEqualTo(409);
    assertThat(damaged.getBody()).doesNotContainKey("reason");
    assertThat(output)
        .contains("reason_code=REFUND_CONCURRENCY_OBSERVATION_INDETERMINATE")
        .contains("reason_code=REFUND_DURABLE_TRUTH_INCONSISTENT")
        .doesNotContain("SQL")
        .doesNotContain("innodb");
  }

  @Test
  void dependencyFailureHasItsOwnServerOnlyReason(CapturedOutput output) {
    RefundExceptionHandler handler = new RefundExceptionHandler();

    var response =
        handler.handleUnavailable(
            new DataAccessResourceFailureException("private persistence detail"));
    var identityResponse =
        handler.handleUnavailable(
            new IdentityVerificationUnavailableException(
                new IllegalStateException("private JWKS detail")));
    var restorationResponse =
        handler.handleUnavailable(
            new MySqlSessionPolicyRestorationException(
                new CannotAcquireLockException(
                    "private restore detail",
                    new SQLException("private restore SQL", "HY000", 1205))));

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody())
        .containsExactlyEntriesOf(
            Map.of("category", "UNAVAILABLE", "message", "Refund service is unavailable"))
        .doesNotContainKey("reason");
    assertThat(identityResponse.getStatusCode().value()).isEqualTo(503);
    assertThat(identityResponse.getBody()).isEqualTo(response.getBody());
    assertThat(restorationResponse.getStatusCode().value()).isEqualTo(503);
    assertThat(restorationResponse.getBody()).isEqualTo(response.getBody());
    assertThat(output)
        .contains("reason_code=REFUND_DEPENDENCY_UNAVAILABLE")
        .doesNotContain("private persistence detail")
        .doesNotContain("private JWKS detail")
        .doesNotContain("private restore detail")
        .doesNotContain("private restore SQL");
  }

  @Test
  void publicRejectionStatusAndReasonInventoryIsClosed() {
    assertThat(RefundException.publicRejectionStatuses())
        .containsExactlyInAnyOrder(400, 401, 403, 404, 409, 429, 503);
    assertThatThrownBy(
            () ->
                new RefundException(
                    500,
                    "UNEXPECTED",
                    RefundRejectionReason.NOT_APPLICABLE,
                    "not a public refund result"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RefundException(
                    409,
                    "CONFLICT",
                    RefundRejectionReason.REFUND_CONCURRENCY_OBSERVATION_INDETERMINATE,
                    "wrong classification"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
