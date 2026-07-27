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
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
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
                  .isEqualTo(EvaluationRejectionReason.TOOL_SANDBOX_NOT_ACTIVE);
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

  @Test
  void statePaymentIntegrityConflictIsAttributedWithoutLeakingReason(CapturedOutput output) {
    EvaluationSandboxController controller =
        new EvaluationSandboxController(null, null, null, null, null);

    var response =
        controller.rejected(
            new EvaluationSandboxException(
                409,
                EvaluationRejectionReason.STATE_COMMITTED_PAYMENT_TRUTH_INCONSISTENT,
                "Evaluation payment truth is inconsistent"));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody())
        .containsExactlyEntriesOf(Map.of("error", "Evaluation payment truth is inconsistent"));
    assertThat(response.getBody().toString())
        .doesNotContain("STATE_COMMITTED_PAYMENT_TRUTH_INCONSISTENT");
    assertThat(output)
        .contains("reason_code=STATE_COMMITTED_PAYMENT_TRUTH_INCONSISTENT")
        .doesNotContain("private payment detail");
  }

  @Test
  void auditPaymentIntegrityConflictIsAttributedWithoutLeakingReason(CapturedOutput output) {
    EvaluationSandboxController controller =
        new EvaluationSandboxController(null, null, null, null, null);

    var response =
        controller.rejected(
            new EvaluationSandboxException(
                409,
                EvaluationRejectionReason.AUDIT_COMMITTED_PAYMENT_TRUTH_INCONSISTENT,
                "Evaluation audit truth is inconsistent"));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody())
        .containsExactlyEntriesOf(Map.of("error", "Evaluation audit truth is inconsistent"));
    assertThat(response.getBody().toString())
        .doesNotContain("AUDIT_COMMITTED_PAYMENT_TRUTH_INCONSISTENT");
    assertThat(output).contains("reason_code=AUDIT_COMMITTED_PAYMENT_TRUTH_INCONSISTENT");
  }

  @Test
  void genericConflictDoesNotAcquirePaymentIntegrityAttribution(CapturedOutput output) {
    EvaluationSandboxController controller =
        new EvaluationSandboxController(null, null, null, null, null);

    var response =
        controller.rejected(
            new EvaluationSandboxException(409, "Evaluation audit truth is inconsistent"));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody())
        .containsExactlyEntriesOf(Map.of("error", "Evaluation audit truth is inconsistent"));
    assertThat(output)
        .doesNotContain("STATE_COMMITTED_PAYMENT_TRUTH_INCONSISTENT")
        .doesNotContain("AUDIT_COMMITTED_PAYMENT_TRUTH_INCONSISTENT");
  }

  @Test
  void nonPaymentAuditIntegrityConflictIsAttributedWithoutLeakingReason(CapturedOutput output) {
    EvaluationSandboxController controller =
        new EvaluationSandboxController(null, null, null, null, null);

    var response =
        controller.rejected(
            new EvaluationSandboxException(
                409,
                EvaluationRejectionReason.STATE_EVALUATION_AUDIT_TRUTH_INCONSISTENT,
                "Evaluation audit truth is inconsistent"));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody())
        .containsExactlyEntriesOf(Map.of("error", "Evaluation audit truth is inconsistent"));
    assertThat(response.getBody().toString())
        .doesNotContain("STATE_EVALUATION_AUDIT_TRUTH_INCONSISTENT");
    assertThat(output)
        .contains("reason_code=STATE_EVALUATION_AUDIT_TRUTH_INCONSISTENT")
        .doesNotContain("STATE_COMMITTED_PAYMENT_TRUTH_INCONSISTENT")
        .doesNotContain("AUDIT_COMMITTED_PAYMENT_TRUTH_INCONSISTENT");
  }

  @Test
  void auditInsertClassifiesOnlyPermissionAndResourceFailuresAsUnavailable() {
    EvaluationSandboxException denied =
        EvaluationCommerceAuditService.auditInsertFailure(
            new BadSqlGrammarException(
                "insert audit",
                "private sql",
                new SQLException("private permission detail", "42000", 1142)));
    EvaluationSandboxException resource =
        EvaluationCommerceAuditService.auditInsertFailure(
            new DataAccessResourceFailureException("private resource detail"));

    assertThat(denied.status()).isEqualTo(503);
    assertThat(denied.reason())
        .isEqualTo(EvaluationRejectionReason.TOOL_AUDIT_PERSISTENCE_UNAVAILABLE);
    assertThat(resource.status()).isEqualTo(503);
    assertThat(resource.reason())
        .isEqualTo(EvaluationRejectionReason.TOOL_AUDIT_PERSISTENCE_UNAVAILABLE);

    var lockTimeout =
        new CannotAcquireLockException(
            "private lock timeout", new SQLException("private lock detail", "41000", 1205));
    var deadlock =
        new CannotAcquireLockException(
            "private deadlock", new SQLException("private lock detail", "40001", 1213));
    var constraint = new DataIntegrityViolationException("private constraint detail");
    var badSql =
        new BadSqlGrammarException(
            "insert audit",
            "private sql",
            new SQLException("private syntax detail", "42000", 1064));

    for (DataAccessException visibleFailure :
        List.<DataAccessException>of(lockTimeout, deadlock, constraint, badSql)) {
      assertThatThrownBy(() -> EvaluationCommerceAuditService.auditInsertFailure(visibleFailure))
          .isSameAs(visibleFailure);
    }
  }
}
