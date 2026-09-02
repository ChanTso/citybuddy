package io.citybuddy.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class ServiceCredentialVerifierTest {
  private static final byte[] FINGERPRINT_KEY = new byte[32];

  @Test
  void cachesOnlySuccessfulProofForTheCurrentPersistedHash() {
    PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    when(passwordEncoder.matches("correct", "hash-v1")).thenReturn(true);
    when(passwordEncoder.matches("wrong", "hash-v1")).thenReturn(false);
    when(passwordEncoder.matches("correct", "hash-v2")).thenReturn(true);
    ServiceCredentialVerifier verifier =
        new ServiceCredentialVerifier(passwordEncoder, FINGERPRINT_KEY);

    assertThat(verifier.matches("agent-service", "correct", "hash-v1")).isTrue();
    assertThat(verifier.matches("agent-service", "correct", "hash-v1")).isTrue();
    assertThat(verifier.matches("agent-service", "wrong", "hash-v1")).isFalse();
    assertThat(verifier.matches("agent-service", "correct", "hash-v2")).isTrue();

    verify(passwordEncoder).matches("correct", "hash-v1");
    verify(passwordEncoder).matches("wrong", "hash-v1");
    verify(passwordEncoder).matches("correct", "hash-v2");
  }

  @Test
  void scopesProofsToTheClientIdentity() {
    PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    when(passwordEncoder.matches("correct", "shared-hash")).thenReturn(true);
    ServiceCredentialVerifier verifier =
        new ServiceCredentialVerifier(passwordEncoder, FINGERPRINT_KEY);

    assertThat(verifier.matches("agent-service", "correct", "shared-hash")).isTrue();
    assertThat(verifier.matches("evaluation-client", "correct", "shared-hash")).isTrue();

    verify(passwordEncoder, times(2)).matches("correct", "shared-hash");
  }

  @Test
  void serializesAColdBurstToOneBcryptVerification() throws Exception {
    PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    when(passwordEncoder.matches("correct", "hash-v1")).thenReturn(true);
    ServiceCredentialVerifier verifier =
        new ServiceCredentialVerifier(passwordEncoder, FINGERPRINT_KEY);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(16)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int index = 0; index < 32; index++) {
        results.add(
            executor.submit(
                () -> {
                  start.await();
                  return verifier.matches("agent-service", "correct", "hash-v1");
                }));
      }
      start.countDown();

      for (Future<Boolean> result : results) {
        assertThat(result.get()).isTrue();
      }
    }

    verify(passwordEncoder).matches("correct", "hash-v1");
  }

  @Test
  void neverSharesAValidCandidatesResultWithAnInvalidCandidate() throws Exception {
    PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    when(passwordEncoder.matches("correct", "hash-v1")).thenReturn(true);
    when(passwordEncoder.matches("wrong", "hash-v1")).thenReturn(false);
    ServiceCredentialVerifier verifier =
        new ServiceCredentialVerifier(passwordEncoder, FINGERPRINT_KEY);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<Boolean> correct =
          executor.submit(
              () -> {
                start.await();
                return verifier.matches("agent-service", "correct", "hash-v1");
              });
      Future<Boolean> wrong =
          executor.submit(
              () -> {
                start.await();
                return verifier.matches("agent-service", "wrong", "hash-v1");
              });
      start.countDown();

      assertThat(correct.get()).isTrue();
      assertThat(wrong.get()).isFalse();
    }

    verify(passwordEncoder).matches("correct", "hash-v1");
    verify(passwordEncoder).matches("wrong", "hash-v1");
  }
}
