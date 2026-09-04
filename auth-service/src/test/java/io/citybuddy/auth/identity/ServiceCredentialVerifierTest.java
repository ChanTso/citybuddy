package io.citybuddy.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class ServiceCredentialVerifierTest {
  private static final String SECRET = "cbsvc_v1_" + "0123456789abcdef".repeat(4);

  @Test
  void verifiesAClientBoundGeneratedCredentialWithoutPasswordHashing() {
    PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    ServiceCredentialVerifier verifier = new ServiceCredentialVerifier(passwordEncoder);
    String encoded = ServiceCredentialVerifier.encodedDigest("agent-service", SECRET);

    assertThat(encoded)
        .isEqualTo("sha256$v1$9b4ba6b7ddad9e69a3b5604cdee2d3929e8c0751b976e5b072d3f907f8c0bc9a");
    assertThat(verifier.matches("agent-service", SECRET, encoded)).isTrue();
    assertThat(verifier.matches("commerce-service", SECRET, encoded)).isFalse();
    assertThat(verifier.matches("agent-service", "cbsvc_v1_" + "f".repeat(64), encoded)).isFalse();
    verifyNoInteractions(passwordEncoder);
  }

  @Test
  void rejectsMalformedGeneratedCredentialsAndUnknownDigestVersions() {
    PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    ServiceCredentialVerifier verifier = new ServiceCredentialVerifier(passwordEncoder);

    assertThat(verifier.matches("agent-service", "short", "sha256$v1$" + "0".repeat(64))).isFalse();
    assertThat(verifier.matches("agent-service", SECRET, "sha256$v2$" + "0".repeat(64))).isFalse();
    assertThat(verifier.matches("agent-service", SECRET, "sha256$v1$" + "z".repeat(64))).isFalse();
    assertThatThrownBy(() -> ServiceCredentialVerifier.encodedDigest("agent-service", "short"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(passwordEncoder);
  }

  @Test
  void retainsBcryptForLegacyServiceCredentialsWithoutCaching() {
    PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    String legacyHash = "$2a$12$" + "x".repeat(53);
    when(passwordEncoder.matches("legacy-secret", legacyHash)).thenReturn(true);
    ServiceCredentialVerifier verifier = new ServiceCredentialVerifier(passwordEncoder);

    assertThat(verifier.matches("agent-service", "legacy-secret", legacyHash)).isTrue();
    assertThat(verifier.matches("agent-service", "legacy-secret", legacyHash)).isTrue();

    verify(passwordEncoder, times(2)).matches("legacy-secret", legacyHash);
  }
}
