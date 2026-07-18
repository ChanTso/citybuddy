package io.citybuddy.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthIdentityTest {
  @TempDir Path tempDirectory;

  private AuthRepository repository;
  private AuthKeySet keys;
  private AuthController controller;
  private BCryptPasswordEncoder passwordEncoder;
  private KeyPair currentKeyPair;
  private KeyPair overlapKeyPair;

  @BeforeEach
  void setUp() throws Exception {
    currentKeyPair = keyPair();
    overlapKeyPair = keyPair();
    Path privatePath =
        writePem("current-private.pem", "PRIVATE KEY", currentKeyPair.getPrivate().getEncoded());
    Path publicPath =
        writePem("current-public.pem", "PUBLIC KEY", currentKeyPair.getPublic().getEncoded());
    Path overlapPath =
        writePem("overlap-public.pem", "PUBLIC KEY", overlapKeyPair.getPublic().getEncoded());
    IdentityProperties properties =
        new IdentityProperties(
            "https://identity.citybuddy.test",
            "citybuddy-web",
            "current-key",
            privatePath.toString(),
            publicPath.toString(),
            "overlap-key",
            overlapPath.toString(),
            Duration.ofMinutes(15),
            Duration.ofMinutes(2),
            Duration.ofSeconds(30),
            List.of("catalog:read", "*", "catalog:*"));
    repository = mock(AuthRepository.class);
    when(repository.publicKeyMetadata())
        .thenReturn(
            List.of(
                new AuthRepository.KeyMetadata(
                    "current-key", "CURRENT", Instant.parse("2026-07-15T00:00:00Z"), null)));
    when(repository.isActiveSubject("user-123")).thenReturn(true);
    passwordEncoder = new BCryptPasswordEncoder(4);
    Clock clock = Clock.systemUTC();
    keys = new AuthKeySet(properties, clock);
    controller =
        new AuthController(
            repository, keys, passwordEncoder, properties, new MockEnvironment(), clock);
  }

  @Test
  void loginIssuesExplicitDirectTokenAndHidesCredentialFailureReason() throws Exception {
    when(repository.findUser("active-user"))
        .thenReturn(
            Optional.of(
                new AuthRepository.UserCredential(
                    "user-123",
                    "ACTIVE",
                    List.of("support:session:create"),
                    passwordEncoder.encode("correct-password"))));
    when(repository.findUser("disabled-user"))
        .thenReturn(
            Optional.of(
                new AuthRepository.UserCredential(
                    "user-disabled",
                    "DISABLED",
                    List.of("support:session:create"),
                    passwordEncoder.encode("correct-password"))));

    AuthController.TokenResponse response =
        controller.login(null, new AuthController.LoginRequest("active-user", "correct-password"));
    SignedJWT jwt = SignedJWT.parse(response.accessToken());

    assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
    assertThat(jwt.getHeader().getKeyID()).isEqualTo("current-key");
    assertThat(jwt.getJWTClaimsSet().getClaim("token_type")).isEqualTo("direct_user");
    assertThat(jwt.getJWTClaimsSet().getClaim("principal_state")).isEqualTo("ACTIVE");
    assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("citybuddy-web");
    assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("user-123");
    assertThat(
            keys.validateDirect(
                    response.accessToken(),
                    "support:session:create",
                    Set.of("current-key", "overlap-key"))
                .subject())
        .isEqualTo("user-123");

    assertThatThrownBy(
            () ->
                controller.login(
                    null, new AuthController.LoginRequest("active-user", "wrong-password")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Invalid credentials");
    assertThatThrownBy(
            () ->
                controller.login(
                    null, new AuthController.LoginRequest("disabled-user", "correct-password")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Invalid credentials");
    assertThatThrownBy(
            () ->
                controller.login(
                    "production-rejects-this",
                    new AuthController.LoginRequest("active-user", "correct-password")))
        .isInstanceOf(IdentityException.class)
        .hasMessageContaining("Evaluation");
  }

  @Test
  void jwksPublishesOnlyConfiguredCurrentAndOverlappingPublicMaterial() {
    when(repository.publicKeyMetadata())
        .thenReturn(
            List.of(
                new AuthRepository.KeyMetadata(
                    "current-key", "CURRENT", Instant.parse("2026-07-15T00:00:00Z"), null),
                new AuthRepository.KeyMetadata(
                    "overlap-key",
                    "OVERLAP",
                    Instant.parse("2026-07-15T00:00:00Z"),
                    Instant.parse("2026-07-15T01:00:00Z"))));

    String response = controller.jwks(null).toString();

    assertThat(response).contains("current-key", "overlap-key", "RSA");
    assertThat(response).doesNotContain("retired-key", "PRIVATE", " d=");
    assertThatThrownBy(() -> controller.jwks("forbidden-production-context"))
        .isInstanceOf(IdentityException.class)
        .hasMessageContaining("Evaluation");
  }

  @Test
  void jwksRejectsOverlapShorterThanMaximumTokenLifetimeAndClockSkew() {
    Instant activated = Instant.parse("2026-07-15T00:00:00Z");
    when(repository.publicKeyMetadata())
        .thenReturn(
            List.of(
                new AuthRepository.KeyMetadata("current-key", "CURRENT", activated, null),
                new AuthRepository.KeyMetadata(
                    "overlap-key", "OVERLAP", activated, activated.plus(Duration.ofMinutes(5)))));

    assertThatThrownBy(() -> controller.jwks(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("overlap");
  }

  @Test
  void directValidationRejectsMalformedIssuerAudienceTimeAndPrincipalState() throws Exception {
    Instant now = Instant.now();

    assertThatThrownBy(
            () -> keys.validateDirect("malformed", "support:session:create", Set.of("current-key")))
        .isInstanceOf(IdentityException.class);
    assertThatThrownBy(
            () ->
                keys.validateDirect(
                    signedDirect(
                        "https://wrong.example",
                        "citybuddy-web",
                        "ACTIVE",
                        now,
                        now.plusSeconds(60)),
                    "support:session:create",
                    Set.of("current-key")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Wrong issuer");
    assertThatThrownBy(
            () ->
                keys.validateDirect(
                    signedDirect(
                        "https://identity.citybuddy.test",
                        "commerce-service",
                        "ACTIVE",
                        now,
                        now.plusSeconds(60)),
                    "support:session:create",
                    Set.of("current-key")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Wrong audience");
    assertThatThrownBy(
            () ->
                keys.validateDirect(
                    signedDirect(
                        "https://identity.citybuddy.test",
                        "citybuddy-web",
                        "ACTIVE",
                        now.minusSeconds(120),
                        now.minusSeconds(60)),
                    "support:session:create",
                    Set.of("current-key")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Expired token");
    assertThatThrownBy(
            () ->
                keys.validateDirect(
                    signedDirect(
                        "https://identity.citybuddy.test",
                        "citybuddy-web",
                        "ACTIVE",
                        now.plusSeconds(60),
                        now.plusSeconds(120)),
                    "support:session:create",
                    Set.of("current-key")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Premature token");
    assertThatThrownBy(
            () ->
                keys.validateDirect(
                    signedDirect(
                        "https://identity.citybuddy.test",
                        "citybuddy-web",
                        "DISABLED",
                        now,
                        now.plusSeconds(60)),
                    "support:session:create",
                    Set.of("current-key")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Inactive principal");
  }

  @Test
  void directValidationRejectsNonStringSandboxInsteadOfTreatingItAsAbsent() throws Exception {
    assertThatThrownBy(
            () ->
                keys.validateDirect(
                    signedSandboxToken("direct_user", List.of("sandbox-1")),
                    "support:session:create",
                    Set.of("current-key")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Production token carries evaluation sandbox");
    assertThatThrownBy(
            () ->
                keys.validateDirect(
                    signedSandboxToken("eval_direct_user", List.of("sandbox-1")),
                    "support:session:create",
                    Set.of("current-key"),
                    "sandbox-1",
                    true))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Missing evaluation sandbox");
  }

  @Test
  void exchangeRequiresIndependentServiceCredentialAndExactBinding() throws Exception {
    when(repository.findService("agent-service"))
        .thenReturn(
            Optional.of(
                new AuthRepository.ServiceCredential(
                    "agent-service",
                    "ACTIVE",
                    List.of("catalog:read", "*", "catalog:*"),
                    passwordEncoder.encode("service-password"))));
    String direct = keys.directToken("user-123", List.of("support:session:create"));
    String basic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("agent-service:service-password".getBytes(StandardCharsets.UTF_8));

    AuthController.TokenResponse response =
        controller.exchange(
            basic,
            "Bearer " + direct,
            null,
            new AuthController.ExchangeRequest("session-123", "user-123", "catalog:read"));
    SignedJWT obo = SignedJWT.parse(response.accessToken());

    assertThat(obo.getJWTClaimsSet().getClaim("token_type")).isEqualTo("agent_obo");
    assertThat(obo.getJWTClaimsSet().getAudience()).containsExactly("commerce-service");
    assertThat(obo.getJWTClaimsSet().getClaim("session")).isEqualTo("session-123");
    assertThat(obo.getJWTClaimsSet().getClaim("scope")).isEqualTo("catalog:read");
    assertThat(obo.getJWTClaimsSet().getJSONObjectClaim("act"))
        .containsEntry("azp", "agent-service");

    assertThatThrownBy(
            () ->
                controller.exchange(
                    basic,
                    "Bearer " + direct,
                    null,
                    new AuthController.ExchangeRequest(
                        "session-123", "other-user", "catalog:read")))
        .isInstanceOf(IdentityException.class)
        .hasMessageContaining("binding");
    assertThatThrownBy(
            () ->
                controller.exchange(
                    basic,
                    "Bearer " + direct,
                    null,
                    new AuthController.ExchangeRequest(
                        "session-123", "user-123", "catalog:read catalog:write")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Exchange is not allowed");
    for (String wildcard : List.of("*", "catalog:*")) {
      assertThatThrownBy(
              () ->
                  controller.exchange(
                      basic,
                      "Bearer " + direct,
                      null,
                      new AuthController.ExchangeRequest("session-123", "user-123", wildcard)))
          .isInstanceOf(IdentityException.class)
          .hasMessage("Exchange is not allowed");
    }
    assertThatThrownBy(
            () ->
                controller.exchange(
                    "Basic "
                        + Base64.getEncoder()
                            .encodeToString("agent-service:wrong".getBytes(StandardCharsets.UTF_8)),
                    "Bearer " + direct,
                    null,
                    new AuthController.ExchangeRequest("session-123", "user-123", "catalog:read")))
        .isInstanceOf(IdentityException.class);

    assertThatThrownBy(
            () ->
                controller.exchange(
                    basic,
                    "Bearer " + direct,
                    null,
                    new AuthController.ExchangeRequest(" ", "user-123", "catalog:read")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Exchange is not allowed");
    assertThatThrownBy(
            () ->
                controller.exchange(
                    basic,
                    "Bearer " + direct,
                    "production-rejects-this",
                    new AuthController.ExchangeRequest("session-123", "user-123", "catalog:read")))
        .isInstanceOf(IdentityException.class)
        .hasMessageContaining("Evaluation");
    assertThatThrownBy(
            () ->
                controller.exchange(
                    basic,
                    "Bearer " + response.accessToken(),
                    null,
                    new AuthController.ExchangeRequest("session-123", "user-123", "catalog:read")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Wrong token type");

    when(repository.findService("other-service"))
        .thenReturn(
            Optional.of(
                new AuthRepository.ServiceCredential(
                    "other-service",
                    "ACTIVE",
                    List.of("catalog:read"),
                    passwordEncoder.encode("service-password"))));
    String otherBasic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("other-service:service-password".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(
            () ->
                controller.exchange(
                    otherBasic,
                    "Bearer " + direct,
                    null,
                    new AuthController.ExchangeRequest("session-123", "user-123", "catalog:read")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Invalid service credential");

    when(repository.isActiveSubject("user-123")).thenReturn(false);
    assertThatThrownBy(
            () ->
                controller.exchange(
                    basic,
                    "Bearer " + direct,
                    null,
                    new AuthController.ExchangeRequest("session-123", "user-123", "catalog:read")))
        .isInstanceOf(IdentityException.class)
        .hasMessageContaining("not active");
    when(repository.isActiveSubject("user-123")).thenReturn(true);

    when(repository.findService("agent-service"))
        .thenReturn(
            Optional.of(
                new AuthRepository.ServiceCredential(
                    "agent-service",
                    "REVOKED",
                    List.of("catalog:read"),
                    passwordEncoder.encode("service-password"))));
    assertThatThrownBy(
            () ->
                controller.exchange(
                    basic,
                    "Bearer " + direct,
                    null,
                    new AuthController.ExchangeRequest("session-123", "user-123", "catalog:read")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Invalid service credential");
  }

  @Test
  void exchangeAcceptsActiveOverlapAndRejectsItAfterRetirement() throws Exception {
    when(repository.findService("agent-service"))
        .thenReturn(
            Optional.of(
                new AuthRepository.ServiceCredential(
                    "agent-service",
                    "ACTIVE",
                    List.of("catalog:read"),
                    passwordEncoder.encode("service-password"))));
    String basic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("agent-service:service-password".getBytes(StandardCharsets.UTF_8));
    Instant now = Instant.now();
    String overlappingDirect =
        signedDirect(
            overlapKeyPair,
            "overlap-key",
            "https://identity.citybuddy.test",
            "citybuddy-web",
            "ACTIVE",
            now,
            now.plusSeconds(60));
    AuthRepository.KeyMetadata current =
        new AuthRepository.KeyMetadata(
            "current-key", "CURRENT", now.minus(Duration.ofMinutes(30)), null);
    AuthRepository.KeyMetadata overlap =
        new AuthRepository.KeyMetadata(
            "overlap-key",
            "OVERLAP",
            now.minus(Duration.ofMinutes(1)),
            now.plus(Duration.ofMinutes(20)));
    when(repository.publicKeyMetadata()).thenReturn(List.of(current, overlap));

    assertThat(
            controller
                .exchange(
                    basic,
                    "Bearer " + overlappingDirect,
                    null,
                    new AuthController.ExchangeRequest("session-123", "user-123", "catalog:read"))
                .tokenType())
        .isEqualTo("Bearer");

    when(repository.publicKeyMetadata()).thenReturn(List.of(current));
    assertThatThrownBy(
            () ->
                controller.exchange(
                    basic,
                    "Bearer " + overlappingDirect,
                    null,
                    new AuthController.ExchangeRequest("session-123", "user-123", "catalog:read")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Unknown signing key");
  }

  private KeyPair keyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private String signedDirect(
      String issuer, String audience, String principalState, Instant issuedAt, Instant expiresAt)
      throws Exception {
    return signedDirect(
        currentKeyPair, "current-key", issuer, audience, principalState, issuedAt, expiresAt);
  }

  private String signedDirect(
      KeyPair keyPair,
      String kid,
      String issuer,
      String audience,
      String principalState,
      Instant issuedAt,
      Instant expiresAt)
      throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject("user-123")
            .claim("token_type", "direct_user")
            .claim("principal_state", principalState)
            .claim("permissions", List.of("support:session:create"))
            .issueTime(Date.from(issuedAt))
            .notBeforeTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt))
            .build();
    SignedJWT jwt =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
    jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
    return jwt.serialize();
  }

  private String signedSandboxToken(String tokenType, Object sandbox) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience("citybuddy-web")
            .subject("user-123")
            .claim("token_type", tokenType)
            .claim("principal_state", "ACTIVE")
            .claim("permissions", List.of("support:session:create"))
            .claim("sandbox", sandbox)
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(60)))
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("current-key").build(), claims);
    jwt.sign(new RSASSASigner((RSAPrivateKey) currentKeyPair.getPrivate()));
    return jwt.serialize();
  }

  private Path writePem(String name, String type, byte[] encoded) throws Exception {
    Path path = tempDirectory.resolve(name);
    String body =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded);
    Files.writeString(
        path,
        "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n",
        StandardCharsets.US_ASCII);
    return path;
  }
}
