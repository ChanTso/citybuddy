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
  private static final String EVALUATION_HANDLE = "A".repeat(43);

  @TempDir Path tempDirectory;

  private AuthRepository repository;
  private AuthKeySet keys;
  private AuthController controller;
  private BCryptPasswordEncoder passwordEncoder;
  private IdentityProperties properties;
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
    properties =
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
            List.of(
                "catalog:read",
                "*",
                "catalog:*",
                "merchant:read",
                "merchant:price:prepare",
                "merchant:price:read",
                "merchant:price:cancel",
                "merchant:price:apply",
                "merchant:admin"));
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
            repository,
            keys,
            passwordEncoder,
            new ServiceCredentialVerifier(passwordEncoder),
            properties,
            new MockEnvironment(),
            clock);
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
    assertThat(jwt.getJWTClaimsSet().getClaim("evaluation_handle")).isNull();
    assertThat(
            keys.validateDirect(
                    response.accessToken(),
                    "support:session:create",
                    Set.of("current-key", "overlap-key"))
                .subject())
        .isEqualTo("user-123");
    assertThat(
            keys.validateDirect(
                    response.accessToken(),
                    "support:session:create",
                    Set.of("current-key", "overlap-key"))
                .evaluationHandle())
        .isNull();

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
    Instant activated = Instant.now();
    when(repository.publicKeyMetadata())
        .thenReturn(
            List.of(
                new AuthRepository.KeyMetadata("current-key", "CURRENT", activated, null),
                new AuthRepository.KeyMetadata(
                    "overlap-key",
                    "OVERLAP",
                    activated.minus(Duration.ofHours(1)),
                    activated.plus(Duration.ofHours(1)))));

    String response = controller.jwks(null).toString();

    assertThat(response).contains("current-key", "overlap-key", "RSA");
    assertThat(response).doesNotContain("retired-key", "PRIVATE", " d=");
    assertThatThrownBy(() -> controller.jwks("forbidden-production-context"))
        .isInstanceOf(IdentityException.class)
        .hasMessageContaining("Evaluation");
  }

  @Test
  void jwksRejectsOverlapOneSecondShorterThanCurrentActivationPlusTokenLifetimeAndSkew() {
    Instant activated = Instant.now();
    when(repository.publicKeyMetadata())
        .thenReturn(
            List.of(
                new AuthRepository.KeyMetadata("current-key", "CURRENT", activated, null),
                new AuthRepository.KeyMetadata(
                    "overlap-key",
                    "OVERLAP",
                    activated.minus(Duration.ofHours(1)),
                    activated.plusSeconds(929))));

    assertThatThrownBy(() -> controller.jwks(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("overlap");
  }

  @Test
  void jwksAcceptsOverlapAtExactCurrentActivationPlusTokenLifetimeAndSkew() {
    Instant activated = Instant.now();
    when(repository.publicKeyMetadata())
        .thenReturn(
            List.of(
                new AuthRepository.KeyMetadata("current-key", "CURRENT", activated, null),
                new AuthRepository.KeyMetadata(
                    "overlap-key",
                    "OVERLAP",
                    activated.minus(Duration.ofHours(1)),
                    activated.plusSeconds(930))));

    assertThat(controller.jwks(null).toString()).contains("current-key", "overlap-key");
  }

  @Test
  void jwksValidatesExpiredOverlapBeforeFilteringItFromPublication() {
    Instant now = Instant.now();
    when(repository.publicKeyMetadata())
        .thenReturn(
            List.of(
                new AuthRepository.KeyMetadata(
                    "current-key", "CURRENT", now.minusSeconds(60), null),
                new AuthRepository.KeyMetadata(
                    "overlap-key",
                    "OVERLAP",
                    now.minus(Duration.ofHours(1)),
                    now.minusSeconds(1))));

    assertThatThrownBy(() -> controller.jwks(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("overlap");
  }

  @Test
  void jwksDoesNotPublishAValidButRetiredOverlap() {
    Instant now = Instant.now();
    when(repository.publicKeyMetadata())
        .thenReturn(
            List.of(
                new AuthRepository.KeyMetadata(
                    "current-key", "CURRENT", now.minus(Duration.ofMinutes(30)), null),
                new AuthRepository.KeyMetadata(
                    "overlap-key",
                    "OVERLAP",
                    now.minus(Duration.ofHours(1)),
                    now.minusSeconds(1))));

    assertThat(controller.jwks(null).toString())
        .contains("current-key")
        .doesNotContain("overlap-key");
  }

  @Test
  void jwksAndLoginRequireExactlyOneConfiguredCurrentKey() {
    Instant activated = Instant.now();
    when(repository.publicKeyMetadata())
        .thenReturn(
            List.of(
                new AuthRepository.KeyMetadata("current-key", "CURRENT", activated, null),
                new AuthRepository.KeyMetadata("overlap-key", "CURRENT", activated, null)));
    when(repository.findUser("active-user"))
        .thenReturn(
            Optional.of(
                new AuthRepository.UserCredential(
                    "user-123",
                    "ACTIVE",
                    List.of("support:session:create"),
                    passwordEncoder.encode("correct-password"))));

    assertThatThrownBy(() -> controller.jwks(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Exactly one configured current");
    assertThatThrownBy(
            () ->
                controller.login(
                    null, new AuthController.LoginRequest("active-user", "correct-password")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Exactly one configured current");
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
  void directValidationRequiresEvaluationHandleOnlyOnEvaluationTokens() throws Exception {
    for (String validHandle :
        List.of(EVALUATION_HANDLE, "-" + "B".repeat(42), "_" + "C".repeat(42))) {
      var validated =
          keys.validateDirect(
              signedSandboxToken("eval_direct_user", "sandbox-1", validHandle),
              "support:session:create",
              Set.of("current-key"),
              "sandbox-1",
              true);

      assertThat(validated.evaluationHandle()).isEqualTo(validHandle);
    }

    for (Object invalidHandle :
        List.of("A".repeat(42), "A".repeat(42) + "+", List.of(EVALUATION_HANDLE))) {
      assertThatThrownBy(
              () ->
                  keys.validateDirect(
                      signedSandboxToken("eval_direct_user", "sandbox-1", invalidHandle),
                      "support:session:create",
                      Set.of("current-key"),
                      "sandbox-1",
                      true))
          .isInstanceOf(IdentityException.class)
          .hasMessage("Invalid evaluation handle");
    }
    assertThatThrownBy(
            () ->
                keys.validateDirect(
                    signedSandboxToken("eval_direct_user", "sandbox-1"),
                    "support:session:create",
                    Set.of("current-key"),
                    "sandbox-1",
                    true))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Invalid evaluation handle");
    assertThatThrownBy(
            () ->
                keys.validateDirect(
                    signedSandboxToken("direct_user", null, EVALUATION_HANDLE),
                    "support:session:create",
                    Set.of("current-key")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Production token carries evaluation handle");
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

    for (String session : List.of("-" + "A".repeat(42), "_" + "A".repeat(42))) {
      AuthController.TokenResponse edgeResponse =
          controller.exchange(
              basic,
              "Bearer " + direct,
              null,
              new AuthController.ExchangeRequest(session, "user-123", "catalog:read"));
      assertThat(SignedJWT.parse(edgeResponse.accessToken()).getJWTClaimsSet().getClaim("session"))
          .isEqualTo(session);
    }

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
  void merchantExchangeIssuesOnlyItsFourExactScopesWithItsOwnActor() throws Exception {
    List<String> scopes =
        List.of(
            "merchant:read",
            "merchant:price:prepare",
            "merchant:price:read",
            "merchant:price:cancel");
    String basic = allowService("merchant-agent", scopes);
    String direct = keys.directToken("user-123", List.of("merchant:session:create"));

    for (String scope : scopes) {
      AuthController.TokenResponse response =
          controller.exchange(
              basic,
              "Bearer " + direct,
              null,
              new AuthController.ExchangeRequest("merchant-session", "user-123", scope));
      JWTClaimsSet claims = SignedJWT.parse(response.accessToken()).getJWTClaimsSet();

      assertThat(claims.getClaim("token_type")).isEqualTo("agent_obo");
      assertThat(claims.getAudience()).containsExactly("commerce-service");
      assertThat(claims.getSubject()).isEqualTo("user-123");
      assertThat(claims.getClaim("session")).isEqualTo("merchant-session");
      assertThat(claims.getClaim("scope")).isEqualTo(scope);
      assertThat(claims.getJSONObjectClaim("act")).containsEntry("azp", "merchant-agent");
    }
  }

  @Test
  void exchangeKeepsMerchantAndSupportScopePoliciesSeparateEvenWithBroadGrants() {
    String merchantBasic = allowService("merchant-agent", properties.exchangeScopes());
    String supportBasic = allowService("agent-service", properties.exchangeScopes());
    String direct =
        keys.directToken("user-123", List.of("support:session:create", "merchant:session:create"));

    for (String scope : List.of("catalog:read", "merchant:price:apply", "merchant:admin")) {
      assertThatThrownBy(
              () ->
                  controller.exchange(
                      merchantBasic,
                      "Bearer " + direct,
                      null,
                      new AuthController.ExchangeRequest("merchant-session", "user-123", scope)))
          .isInstanceOf(IdentityException.class)
          .hasMessage("Exchange is not allowed");
    }
    for (String scope : properties.exchangeScopes()) {
      if (scope.startsWith("merchant:")) {
        assertThatThrownBy(
                () ->
                    controller.exchange(
                        supportBasic,
                        "Bearer " + direct,
                        null,
                        new AuthController.ExchangeRequest("support-session", "user-123", scope)))
            .isInstanceOf(IdentityException.class)
            .hasMessage("Exchange is not allowed");
      }
    }
  }

  @Test
  void merchantExchangeRequiresItsOwnSessionPermissionAndBoundSubject() {
    String merchantBasic = allowService("merchant-agent", List.of("merchant:read"));
    String supportBasic = allowService("agent-service", List.of("catalog:read"));
    String supportDirect = keys.directToken("user-123", List.of("support:session:create"));
    String merchantDirect = keys.directToken("user-123", List.of("merchant:session:create"));

    assertThatThrownBy(
            () ->
                controller.exchange(
                    merchantBasic,
                    "Bearer " + supportDirect,
                    null,
                    new AuthController.ExchangeRequest(
                        "merchant-session", "user-123", "merchant:read")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Missing permission");
    assertThatThrownBy(
            () ->
                controller.exchange(
                    supportBasic,
                    "Bearer " + merchantDirect,
                    null,
                    new AuthController.ExchangeRequest(
                        "support-session", "user-123", "catalog:read")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Missing permission");
    assertThatThrownBy(
            () ->
                controller.exchange(
                    merchantBasic,
                    "Bearer " + merchantDirect,
                    null,
                    new AuthController.ExchangeRequest(
                        "merchant-session", "other-user", "merchant:read")))
        .isInstanceOf(IdentityException.class)
        .hasMessageContaining("binding");
  }

  @Test
  void merchantExchangeStillRequiresBothServiceAndDeploymentScopeGrants() {
    String basic = allowService("merchant-agent", List.of("merchant:read"));
    String direct = keys.directToken("user-123", List.of("merchant:session:create"));

    assertThatThrownBy(
            () ->
                controller.exchange(
                    basic,
                    "Bearer " + direct,
                    null,
                    new AuthController.ExchangeRequest(
                        "merchant-session", "user-123", "merchant:price:prepare")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Exchange is not allowed");

    IdentityProperties supportOnlyProperties =
        new IdentityProperties(
            properties.issuer(),
            properties.userAudience(),
            properties.currentKid(),
            properties.currentPrivateKeyPath(),
            properties.currentPublicKeyPath(),
            properties.overlapKid(),
            properties.overlapPublicKeyPath(),
            properties.directTtl(),
            properties.oboTtl(),
            properties.clockSkew(),
            List.of("catalog:read"));
    AuthController supportOnlyController =
        new AuthController(
            repository,
            keys,
            passwordEncoder,
            new ServiceCredentialVerifier(passwordEncoder),
            supportOnlyProperties,
            new MockEnvironment(),
            Clock.systemUTC());

    assertThatThrownBy(
            () ->
                supportOnlyController.exchange(
                    basic,
                    "Bearer " + direct,
                    null,
                    new AuthController.ExchangeRequest(
                        "merchant-session", "user-123", "merchant:read")))
        .isInstanceOf(IdentityException.class)
        .hasMessage("Exchange is not allowed");
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

    AuthRepository.KeyMetadata retiredOverlap =
        new AuthRepository.KeyMetadata(
            "overlap-key", "OVERLAP", now.minus(Duration.ofHours(1)), now.minusSeconds(1));
    when(repository.publicKeyMetadata()).thenReturn(List.of(current, retiredOverlap));
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
    return signedSandboxToken(tokenType, sandbox, null);
  }

  private String signedSandboxToken(String tokenType, Object sandbox, Object evaluationHandle)
      throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet.Builder builder =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience("citybuddy-web")
            .subject("user-123")
            .claim("token_type", tokenType)
            .claim("principal_state", "ACTIVE")
            .claim("permissions", List.of("support:session:create"))
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(60)));
    if (sandbox != null) {
      builder.claim("sandbox", sandbox);
    }
    if (evaluationHandle != null) {
      builder.claim("evaluation_handle", evaluationHandle);
    }
    JWTClaimsSet claims = builder.build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("current-key").build(), claims);
    jwt.sign(new RSASSASigner((RSAPrivateKey) currentKeyPair.getPrivate()));
    return jwt.serialize();
  }

  private String allowService(String clientId, List<String> scopes) {
    when(repository.findService(clientId))
        .thenReturn(
            Optional.of(
                new AuthRepository.ServiceCredential(
                    clientId, "ACTIVE", scopes, passwordEncoder.encode("service-password"))));
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((clientId + ":service-password").getBytes(StandardCharsets.UTF_8));
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
