package io.citybuddy.auth.identity;

import io.citybuddy.auth.identity.AuthKeySet.DirectPrincipal;
import io.citybuddy.auth.identity.AuthKeySet.IssuedToken;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "citybuddy.identity.enabled", havingValue = "true")
public final class AuthController {
  private static final String SESSION_PERMISSION = "support:session:create";
  private static final String EXCHANGE_SERVICE = "agent-service";
  private static final String MERCHANT_SERVICE = "merchant-agent";
  private static final String MERCHANT_SESSION_PERMISSION = "merchant:session:create";
  private static final Set<String> MERCHANT_SCOPES =
      Set.of(
          "merchant:read",
          "merchant:price:prepare",
          "merchant:price:read",
          "merchant:price:cancel");

  private final AuthRepository repository;
  private final AuthKeySet keys;
  private final PasswordEncoder passwordEncoder;
  private final ServiceCredentialVerifier serviceCredentialVerifier;
  private final IdentityProperties properties;
  private final Clock clock;
  private final boolean evaluationProfile;

  public AuthController(
      AuthRepository repository,
      AuthKeySet keys,
      PasswordEncoder passwordEncoder,
      ServiceCredentialVerifier serviceCredentialVerifier,
      IdentityProperties properties,
      Environment environment,
      Clock clock) {
    this.repository = repository;
    this.keys = keys;
    this.passwordEncoder = passwordEncoder;
    this.serviceCredentialVerifier = serviceCredentialVerifier;
    this.properties = properties;
    this.clock = clock;
    this.evaluationProfile = environment.acceptsProfiles(Profiles.of("evaluation"));
  }

  @PostMapping("/auth/login")
  public TokenResponse login(
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox,
      @RequestBody LoginRequest request) {
    rejectEvaluationHeader(evalSandbox);
    if (request.loginIdentifier() == null || request.password() == null) {
      throw new IdentityException(401, "Invalid credentials");
    }
    AuthRepository.UserCredential user =
        repository
            .findUser(request.loginIdentifier())
            .filter(candidate -> "ACTIVE".equals(candidate.state()))
            .filter(
                candidate -> passwordEncoder.matches(request.password(), candidate.passwordHash()))
            .orElseThrow(() -> new IdentityException(401, "Invalid credentials"));
    requireCurrentSigningKey();
    return new TokenResponse(
        keys.directToken(user.subject(), user.permissions()),
        "Bearer",
        properties.directTtl().toSeconds());
  }

  @GetMapping("/auth/jwks")
  public Map<String, Object> jwks(
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox) {
    rejectEvaluationHeader(evalSandbox);
    var metadata = repository.publicKeyMetadata();
    return keys.jwks(activeSigningKids(metadata));
  }

  @PostMapping("/auth/token/exchange")
  public TokenResponse exchange(
      @RequestHeader("Authorization") String authorization,
      @RequestHeader("X-User-Authorization") String userAuthorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox,
      @RequestBody ExchangeRequest request) {
    BasicCredential basic = parseBasic(authorization);
    AuthRepository.ServiceCredential service =
        repository
            .findService(basic.clientId())
            .filter(
                candidate ->
                    EXCHANGE_SERVICE.equals(candidate.clientId())
                        || MERCHANT_SERVICE.equals(candidate.clientId()))
            .filter(candidate -> "ACTIVE".equals(candidate.state()))
            .filter(
                candidate ->
                    serviceCredentialVerifier.matches(
                        candidate.clientId(), basic.secret(), candidate.credentialHash()))
            .orElseThrow(() -> new IdentityException(401, "Invalid service credential"));

    if (!hasText(request.sessionId())
        || !hasText(request.userSubject())
        || !hasText(request.scope())
        || request.scope().contains(" ")
        || request.scope().contains("*")
        || !allowsServiceScope(service.clientId(), request.scope())
        || !service.allowedScopes().contains(request.scope())
        || !properties.exchangeScopes().contains(request.scope())) {
      throw new IdentityException(403, "Exchange is not allowed");
    }

    var signingMetadata = repository.publicKeyMetadata();
    DirectPrincipal principal =
        keys.validateDirect(
            parseBearer(userAuthorization),
            MERCHANT_SERVICE.equals(service.clientId())
                ? MERCHANT_SESSION_PERMISSION
                : SESSION_PERMISSION,
            activeSigningKids(signingMetadata),
            evalSandbox,
            evaluationProfile);
    if (!principal.subject().equals(request.userSubject())) {
      throw new IdentityException(403, "Session binding does not match direct user");
    }
    boolean active =
        principal.sandboxId() == null
            ? repository.isActiveSubject(principal.subject())
            : repository.isActiveEvaluationSubject(
                principal.subject(), principal.sandboxId(), clock.instant());
    if (!active) {
      throw new IdentityException(403, "Principal is not active");
    }
    requireCurrentSigningKey(signingMetadata);
    IssuedToken obo =
        keys.oboToken(
            principal.subject(),
            request.sessionId(),
            request.scope(),
            service.clientId(),
            principal.sandboxId(),
            principal.expiresAt());
    return new TokenResponse(obo.value(), "Bearer", obo.expiresIn());
  }

  private static boolean allowsServiceScope(String clientId, String scope) {
    return MERCHANT_SERVICE.equals(clientId)
        ? MERCHANT_SCOPES.contains(scope)
        : !scope.startsWith("merchant:");
  }

  private static BasicCredential parseBasic(String authorization) {
    try {
      if (!authorization.startsWith("Basic ")) {
        throw new IllegalArgumentException();
      }
      String decoded =
          new String(
              Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
      int separator = decoded.indexOf(':');
      if (separator <= 0 || separator == decoded.length() - 1) {
        throw new IllegalArgumentException();
      }
      return new BasicCredential(decoded.substring(0, separator), decoded.substring(separator + 1));
    } catch (IllegalArgumentException exception) {
      throw new IdentityException(401, "Invalid service credential");
    }
  }

  private static String parseBearer(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      throw new IdentityException(401, "Invalid direct user credential");
    }
    return authorization.substring(7);
  }

  private AuthRepository.KeyMetadata requireCurrentSigningKey() {
    return requireCurrentSigningKey(repository.publicKeyMetadata());
  }

  private AuthRepository.KeyMetadata requireCurrentSigningKey(
      java.util.List<AuthRepository.KeyMetadata> signingMetadata) {
    var currentKeys =
        signingMetadata.stream().filter(metadata -> "CURRENT".equals(metadata.state())).toList();
    if (currentKeys.size() != 1 || !properties.currentKid().equals(currentKeys.getFirst().kid())) {
      throw new IllegalStateException(
          "Exactly one configured current signing key must be published");
    }
    return currentKeys.getFirst();
  }

  private Set<String> activeSigningKids(
      java.util.List<AuthRepository.KeyMetadata> signingMetadata) {
    AuthRepository.KeyMetadata current = requireCurrentSigningKey(signingMetadata);
    validateKeyOverlap(signingMetadata, current);
    var now = clock.instant();
    Set<String> activeKids = new HashSet<>();
    signingMetadata.stream()
        .filter(
            item ->
                "CURRENT".equals(item.state())
                    || ("OVERLAP".equals(item.state()) && item.retireAfter().isAfter(now)))
        .forEach(item -> activeKids.add(item.kid()));
    return Set.copyOf(activeKids);
  }

  private void validateKeyOverlap(
      java.util.List<AuthRepository.KeyMetadata> metadata, AuthRepository.KeyMetadata current) {
    var minimumOverlap =
        (properties.directTtl().compareTo(properties.oboTtl()) >= 0
                ? properties.directTtl()
                : properties.oboTtl())
            .plus(properties.clockSkew());
    metadata.stream()
        .filter(item -> "OVERLAP".equals(item.state()))
        .forEach(
            item -> {
              if (item.retireAfter() == null
                  || item.retireAfter().isBefore(current.activatedAt().plus(minimumOverlap))) {
                throw new IllegalStateException(
                    "Signing-key overlap is shorter than token lifetime");
              }
            });
  }

  private static void rejectEvaluationHeader(String evalSandbox) {
    if (evalSandbox != null) {
      throw new IdentityException(401, "Evaluation context is not enabled");
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public record LoginRequest(String loginIdentifier, String password) {}

  public record ExchangeRequest(String sessionId, String userSubject, String scope) {}

  public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}

  private record BasicCredential(String clientId, String secret) {}
}
