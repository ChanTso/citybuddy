package io.citybuddy.commerce.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EvaluationViewServiceAttributionTest {
  private static final String SANDBOX_ID = "sandbox-1";
  private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

  private EvaluationViewRepository repository;
  private EvaluationViewService service;

  @BeforeEach
  void setUp() {
    repository = mock(EvaluationViewRepository.class);
    EvaluationSandboxProperties properties = mock(EvaluationSandboxProperties.class);
    when(properties.buildId()).thenReturn("test-build");
    when(properties.schemaCompatibility()).thenReturn("test-schema");
    when(repository.sandbox(SANDBOX_ID))
        .thenReturn(
            Optional.of(
                new EvaluationViewRepository.SandboxView(
                    SANDBOX_ID,
                    "ACTIVE",
                    "VALID",
                    null,
                    0,
                    NOW.plusSeconds(60),
                    NOW.minusSeconds(60),
                    null,
                    null,
                    1)));
    when(repository.products(SANDBOX_ID)).thenReturn(List.of());
    when(repository.effects(SANDBOX_ID)).thenReturn(List.of());
    when(repository.payments(SANDBOX_ID)).thenReturn(List.of());
    service = new EvaluationViewService(repository, properties, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void statePaymentDamageUsesStateSpecificReason() {
    when(repository.auditReferencesConsistency(
            SANDBOX_ID,
            io.citybuddy.commerce.payment.CommittedPaymentTruthResolver.CommittedPaymentCaller
                .EVALUATION_STATE))
        .thenReturn(EvaluationViewRepository.AuditConsistency.PAYMENT_TRUTH_INCONSISTENT);

    assertThatThrownBy(() -> service.state(SANDBOX_ID))
        .isInstanceOfSatisfying(
            EvaluationSandboxException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(EvaluationRejectionReason.STATE_COMMITTED_PAYMENT_TRUTH_INCONSISTENT);
            });
  }

  @Test
  void statePaymentProjectionDamageUsesStateSpecificReason() {
    when(repository.auditReferencesConsistency(
            SANDBOX_ID,
            io.citybuddy.commerce.payment.CommittedPaymentTruthResolver.CommittedPaymentCaller
                .EVALUATION_STATE))
        .thenReturn(EvaluationViewRepository.AuditConsistency.CONSISTENT);
    when(repository.payments(SANDBOX_ID))
        .thenReturn(
            List.of(
                new EvaluationViewRepository.PaymentView(
                    "attempt-1", null, "order-1", 1800, "CNY", "PENDING", 1, null, "PAID", 2, 1)));

    assertThatThrownBy(() -> service.state(SANDBOX_ID))
        .isInstanceOfSatisfying(
            EvaluationSandboxException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(EvaluationRejectionReason.STATE_COMMITTED_PAYMENT_TRUTH_INCONSISTENT);
            });
  }

  @Test
  void auditPaymentDamageUsesAuditSpecificReason() {
    when(repository.auditReferencesConsistency(
            SANDBOX_ID,
            io.citybuddy.commerce.payment.CommittedPaymentTruthResolver.CommittedPaymentCaller
                .EVALUATION_AUDIT))
        .thenReturn(EvaluationViewRepository.AuditConsistency.PAYMENT_TRUTH_INCONSISTENT);

    assertThatThrownBy(
            () ->
                service.audit(
                    SANDBOX_ID,
                    "session-1",
                    new EvaluationViewRequestParser.AuditPageRequest(0, 20)))
        .isInstanceOfSatisfying(
            EvaluationSandboxException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(EvaluationRejectionReason.AUDIT_COMMITTED_PAYMENT_TRUTH_INCONSISTENT);
            });
  }

  @Test
  void stateNonPaymentAuditDamageUsesStateAuditReason() {
    when(repository.auditReferencesConsistency(
            SANDBOX_ID,
            io.citybuddy.commerce.payment.CommittedPaymentTruthResolver.CommittedPaymentCaller
                .EVALUATION_STATE))
        .thenReturn(EvaluationViewRepository.AuditConsistency.NON_PAYMENT_AUDIT_TRUTH_INCONSISTENT);

    assertThatThrownBy(() -> service.state(SANDBOX_ID))
        .isInstanceOfSatisfying(
            EvaluationSandboxException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(EvaluationRejectionReason.STATE_EVALUATION_AUDIT_TRUTH_INCONSISTENT);
            });
  }

  @Test
  void auditNonPaymentAuditDamageUsesAuditReason() {
    when(repository.auditReferencesConsistency(
            SANDBOX_ID,
            io.citybuddy.commerce.payment.CommittedPaymentTruthResolver.CommittedPaymentCaller
                .EVALUATION_AUDIT))
        .thenReturn(EvaluationViewRepository.AuditConsistency.NON_PAYMENT_AUDIT_TRUTH_INCONSISTENT);

    assertThatThrownBy(
            () ->
                service.audit(
                    SANDBOX_ID,
                    "session-1",
                    new EvaluationViewRequestParser.AuditPageRequest(0, 20)))
        .isInstanceOfSatisfying(
            EvaluationSandboxException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(409);
              assertThat(exception.reason())
                  .isEqualTo(EvaluationRejectionReason.AUDIT_EVALUATION_AUDIT_TRUTH_INCONSISTENT);
            });
  }
}
