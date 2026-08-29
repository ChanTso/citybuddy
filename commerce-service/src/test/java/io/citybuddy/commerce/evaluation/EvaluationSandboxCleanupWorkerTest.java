package io.citybuddy.commerce.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.citybuddy.commerce.evaluation.EvaluationSandboxRepository.Sandbox;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EvaluationSandboxCleanupWorkerTest {
  private static final Instant NOW = Instant.parse("2026-08-29T08:00:00Z");
  private static final String SANDBOX_ID = "sandbox-late-cleanup";

  private final EvaluationSandboxRepository repository = mock(EvaluationSandboxRepository.class);
  private final EvaluationIdentityClient identity = mock(EvaluationIdentityClient.class);
  private final EvaluationSandboxProperties properties = mock(EvaluationSandboxProperties.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final EvaluationSandboxCleanupWorker worker =
      new EvaluationSandboxCleanupWorker(repository, identity, properties, clock);

  @Test
  void cleanupDoesNotCreatePrimaryAfterTheOriginalProvisioningDeadline() {
    Sandbox sandbox = lateSandbox("UNPROVISIONED", null, null);
    claim(sandbox);

    assertThat(worker.cleanupNow(SANDBOX_ID)).isFalse();

    verifyNoInteractions(identity);
    verify(repository, never()).bindCleanupHandle(anyString(), anyString(), any());
  }

  @Test
  void cleanupDoesNotCreatePaymentOwnerAfterTheOriginalProvisioningDeadline() {
    Sandbox sandbox = lateSandbox("REVOKED", "payment-owner", "UNPROVISIONED");
    claim(sandbox);

    assertThat(worker.cleanupNow(SANDBOX_ID)).isFalse();

    verifyNoInteractions(identity);
    verify(repository, never()).bindPaymentOwnerCleanupHandle(anyString(), anyString(), any());
  }

  private void claim(Sandbox sandbox) {
    when(properties.maxCleanupAttempts()).thenReturn(3);
    when(properties.cleanupRetry()).thenReturn(Duration.ofSeconds(5));
    when(repository.claimOne(SANDBOX_ID, NOW, 3, Duration.ofSeconds(5)))
        .thenReturn(Optional.of(sandbox));
  }

  private static Sandbox lateSandbox(
      String primaryState, String paymentOwnerLabel, String paymentOwnerState) {
    Sandbox sandbox = mock(Sandbox.class);
    when(sandbox.authState()).thenReturn(primaryState);
    when(sandbox.paymentOwnerTestUserLabel()).thenReturn(paymentOwnerLabel);
    when(sandbox.paymentOwnerAuthState()).thenReturn(paymentOwnerState);
    when(sandbox.provisioningDueAt()).thenReturn(NOW.minusSeconds(1));
    return sandbox;
  }
}
