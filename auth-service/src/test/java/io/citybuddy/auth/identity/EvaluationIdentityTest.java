package io.citybuddy.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jwt.SignedJWT;
import io.citybuddy.auth.identity.AuthRepository.EvaluationPrincipal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationIdentityTest {
  private static final Instant NOW = Instant.parse("2026-07-18T06:00:00Z");

  @TempDir Path tempDirectory;

  private AuthRepository repository;
  private AuthKeySet keys;
  private EvaluationIdentityService service;

  @BeforeEach
  void setUp() throws Exception {
    KeyPair keyPair = keyPair();
    Path privatePath =
        writePem("current-private.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path publicPath =
        writePem("current-public.pem", "PUBLIC KEY", keyPair.getPublic().getEncoded());
    IdentityProperties properties =
        new IdentityProperties(
            "https://identity.citybuddy.test",
            "citybuddy-web",
            "current-key",
            privatePath.toString(),
            publicPath.toString(),
            null,
            null,
            Duration.ofMinutes(15),
            Duration.ofMinutes(2),
            Duration.ofSeconds(30),
            List.of("catalog:read"));
    repository = mock(AuthRepository.class);
    when(repository.publicKeyMetadata())
        .thenReturn(
            List.of(
                new AuthRepository.KeyMetadata(
                    "current-key", "CURRENT", NOW.minusSeconds(60), null)));
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    keys = new AuthKeySet(properties, clock);
    service = new EvaluationIdentityService(repository, keys, properties, clock);
  }

  @Test
  void provisioningPersistsOpaqueServerIdentityAndReturnsSameIntent() {
    AtomicReference<EvaluationPrincipal> stored = new AtomicReference<>();
    when(repository.findEvaluationByProvisionKey("provision-1"))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
    when(repository.findEvaluationBySandboxCase("sandbox-1", "case-1"))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
    doAnswer(
            invocation -> {
              stored.set(invocation.getArgument(0));
              return 1;
            })
        .when(repository)
        .insertEvaluationPrincipal(any());

    var first = service.provision("sandbox-1", "case-1", "test-user-1", 300, "provision-1");
    var replay = service.provision("sandbox-1", "case-1", "test-user-1", 300, "provision-1");

    assertThat(replay).isEqualTo(first);
    assertThat(first.handle()).hasSize(43).doesNotContain("test-user-1");
    assertThat(stored.get().subject()).startsWith("eval-").doesNotContain("test-user-1");
    assertThat(stored.get().expiresAt()).isEqualTo(NOW.plusSeconds(300));
    assertThat(stored.get().permissionList())
        .containsExactly("support:session:create", "support:chat");
    verify(repository).insertEvaluationPrincipal(any());
  }

  @Test
  void provisioningConvertsUniqueBindingRaceToConflict() {
    EvaluationPrincipal winner = principal("winner", "winner-key", "PROVISIONED", null, null);
    when(repository.findEvaluationByProvisionKey("loser-key")).thenReturn(Optional.empty());
    when(repository.findEvaluationBySandboxCase("sandbox-1", "case-1"))
        .thenReturn(Optional.empty(), Optional.of(winner));

    assertThatThrownBy(
            () -> service.provision("sandbox-1", "case-1", "test-user-1", 300, "loser-key"))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Conflicting evaluation provisioning");
  }

  @Test
  void tokenAndOboAreSignedSandboxBoundRedactedAndCappedByProvisioningExpiry() throws Exception {
    EvaluationPrincipal principal =
        principal("opaque-handle", "provision-key", "PROVISIONED", null, null);
    when(repository.findEvaluationByHandle("opaque-handle")).thenReturn(Optional.of(principal));

    AuthController.TokenResponse response = service.issueToken("opaque-handle", "sandbox-1");
    SignedJWT direct = SignedJWT.parse(response.accessToken());

    assertThat(direct.getJWTClaimsSet().getClaim("token_type"))
        .isEqualTo(AuthKeySet.EVALUATION_DIRECT_TYPE);
    assertThat(direct.getJWTClaimsSet().getClaim("sandbox")).isEqualTo("sandbox-1");
    assertThat(direct.getJWTClaimsSet().getExpirationTime().toInstant())
        .isEqualTo(principal.expiresAt());
    assertThat(direct.getJWTClaimsSet().getClaims().keySet())
        .doesNotContain(
            "opaque_handle", "case_correlation", "test_user_label", "provision_idempotency_key");

    var validated =
        keys.validateDirect(
            response.accessToken(),
            "support:session:create",
            Set.of("current-key"),
            "sandbox-1",
            true);
    var obo =
        keys.oboToken(
            validated.subject(),
            "session-1",
            "catalog:read",
            "agent-service",
            validated.sandboxId(),
            validated.expiresAt());
    SignedJWT derived = SignedJWT.parse(obo.value());
    assertThat(derived.getJWTClaimsSet().getClaim("sandbox")).isEqualTo("sandbox-1");
    assertThat(derived.getJWTClaimsSet().getExpirationTime().toInstant())
        .isEqualTo(principal.expiresAt());

    assertThatThrownBy(
            () ->
                keys.validateDirect(
                    response.accessToken(),
                    "support:session:create",
                    Set.of("current-key"),
                    "sandbox-2",
                    true))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Evaluation sandbox mismatch");
  }

  @Test
  void revokeIsBindingPrivateIdempotentAndPreventsIssuance() {
    EvaluationPrincipal provisioned =
        principal("opaque-handle", "provision-key", "PROVISIONED", null, null);
    EvaluationPrincipal revoked =
        principal("opaque-handle", "provision-key", "REVOKED", "revoke-1", NOW);
    when(repository.findEvaluationByHandle("opaque-handle"))
        .thenReturn(Optional.of(provisioned), Optional.of(revoked), Optional.of(revoked));
    when(repository.revokeEvaluationPrincipal(
            "opaque-handle", "sandbox-1", "case-1", "revoke-1", NOW))
        .thenReturn(1);

    assertThat(service.revoke("opaque-handle", "sandbox-1", "case-1", "revoke-1").state())
        .isEqualTo("REVOKED");
    assertThat(service.revoke("opaque-handle", "sandbox-1", "case-1", "revoke-1").state())
        .isEqualTo("REVOKED");
    assertThatThrownBy(() -> service.issueToken("opaque-handle", "sandbox-1"))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Invalid evaluation handle");

    when(repository.findEvaluationByHandle("other-handle")).thenReturn(Optional.of(provisioned));
    assertThatThrownBy(() -> service.revoke("other-handle", "sandbox-2", "case-1", "revoke-2"))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Evaluation principal not found");
    verify(repository, never())
        .revokeEvaluationPrincipal("other-handle", "sandbox-2", "case-1", "revoke-2", NOW);
  }

  @Test
  void tokenIssuanceRequiresCurrentSigningMetadata() {
    EvaluationPrincipal principal =
        principal("opaque-handle", "provision-key", "PROVISIONED", null, null);
    when(repository.findEvaluationByHandle("opaque-handle")).thenReturn(Optional.of(principal));
    when(repository.publicKeyMetadata()).thenReturn(List.of());

    assertThatThrownBy(() -> service.issueToken("opaque-handle", "sandbox-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Current signing key is not published");
  }

  private EvaluationPrincipal principal(
      String handle, String provisionKey, String state, String revokeKey, Instant revokedAt) {
    return new EvaluationPrincipal(
        "00000000-0000-0000-0000-000000000100",
        handle,
        "eval-subject-1",
        "sandbox-1",
        "case-1",
        "test-user-1",
        "support:session:create support:chat",
        provisionKey,
        90,
        state,
        NOW.plusSeconds(90),
        revokeKey,
        revokedAt);
  }

  private KeyPair keyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private Path writePem(String name, String type, byte[] bytes) throws Exception {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
    Path path = tempDirectory.resolve(name);
    Files.writeString(
        path,
        "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n",
        StandardCharsets.US_ASCII);
    return path;
  }
}
