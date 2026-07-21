package io.citybuddy.commerce.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.citybuddy.commerce.identity.IdentityVerificationUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(OutputCaptureExtension.class)
class EvaluationAvailabilityClassificationTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void auditLivenessDistinguishesIndeterminateReadFromConfirmedInactive() {
    JdbcTemplate unavailableJdbc = mock(JdbcTemplate.class);
    when(unavailableJdbc.query(
            anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(), any(), any()))
        .thenThrow(new QueryTimeoutException("controlled timeout"));
    EvaluationCommerceAuditService unavailable =
        new EvaluationCommerceAuditService(unavailableJdbc, CLOCK);

    assertThatThrownBy(
            () ->
                unavailable.observeProduct(
                    "sandbox-1",
                    "session-1",
                    "trace-1",
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    "product-1"))
        .isInstanceOf(QueryTimeoutException.class);

    JdbcTemplate inactiveJdbc = mock(JdbcTemplate.class);
    when(inactiveJdbc.query(
            anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(), any(), any()))
        .thenReturn(List.of());
    EvaluationCommerceAuditService inactive =
        new EvaluationCommerceAuditService(inactiveJdbc, CLOCK);

    assertThatThrownBy(
            () ->
                inactive.observeProduct(
                    "sandbox-1",
                    "session-1",
                    "trace-1",
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    "product-1"))
        .isInstanceOfSatisfying(
            EvaluationSandboxException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(403);
              assertThat(exception.reason())
                  .isEqualTo(EvaluationRejectionReason.AUDIT_SANDBOX_NOT_ACTIVE);
            });
  }

  @Test
  void accessDistinguishesIndeterminateReadFromConfirmedInactive() {
    EvaluationSandboxRepository repository = mock(EvaluationSandboxRepository.class);
    EvaluationSandboxAccess access = new EvaluationSandboxAccess(repository, CLOCK);
    doThrow(new QueryTimeoutException("controlled timeout"))
        .when(repository)
        .isActive("sandbox-1", CLOCK.instant());

    assertThatThrownBy(() -> access.requireActive("sandbox-1"))
        .isInstanceOf(QueryTimeoutException.class);

    doReturn(false).when(repository).isActive("sandbox-1", CLOCK.instant());
    assertThatThrownBy(() -> access.requireActive("sandbox-1"))
        .isInstanceOfSatisfying(
            EvaluationSandboxException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(403);
              assertThat(exception.reason())
                  .isEqualTo(EvaluationRejectionReason.ACCESS_SANDBOX_NOT_ACTIVE);
            });
  }

  @Test
  void paymentLockDistinguishesIndeterminateReadFromConfirmedMissing() {
    JdbcTemplate unavailableJdbc = mock(JdbcTemplate.class);
    when(unavailableJdbc.query(
            anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any()))
        .thenThrow(new QueryTimeoutException("controlled connection exhaustion"));

    assertThatThrownBy(
            () -> new EvaluationSandboxRepository(unavailableJdbc).lockForPayment("sandbox-1"))
        .isInstanceOf(QueryTimeoutException.class);

    JdbcTemplate missingJdbc = mock(JdbcTemplate.class);
    when(missingJdbc.query(
            anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any()))
        .thenReturn(List.of());
    assertThatThrownBy(
            () -> new EvaluationSandboxRepository(missingJdbc).lockForPayment("sandbox-1"))
        .isInstanceOfSatisfying(
            EvaluationSandboxException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(403);
              assertThat(exception.reason())
                  .isEqualTo(EvaluationRejectionReason.PAYMENT_SANDBOX_NOT_FOUND);
            });
  }

  @Test
  void livenessBoundsIdentityDependencyFailureAsUnavailable(CapturedOutput output) {
    EvaluationSandboxController controller =
        new EvaluationSandboxController(null, null, null, null, null);

    var response =
        controller.identityUnavailable(
            new IdentityVerificationUnavailableException(
                new IllegalStateException("private dependency detail")));

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody()).containsExactlyEntriesOf(Map.of("error", "Service unavailable"));
    assertThat(output)
        .contains("reason_code=LIVENESS_DIRECT_USER_JWKS_UNAVAILABLE")
        .doesNotContain("private dependency detail");
  }

  @Test
  void livenessMismatchRemainsAnAttributedForbidden(CapturedOutput output) {
    EvaluationSandboxController controller =
        new EvaluationSandboxController(null, null, null, null, null);

    var response =
        controller.rejected(
            new EvaluationSandboxException(
                403,
                EvaluationRejectionReason.LIVENESS_SANDBOX_MISMATCH,
                "private mismatch detail"));

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    assertThat(response.getBody()).containsExactlyEntriesOf(Map.of("error", "Forbidden"));
    assertThat(output)
        .contains("reason_code=LIVENESS_SANDBOX_MISMATCH")
        .doesNotContain("private mismatch detail");
  }
}
