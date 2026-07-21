package io.citybuddy.commerce.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class OboAuthorizer {
  private final OboProperties properties;
  private final JwksLoader loader;
  private final Clock clock;
  private final boolean evaluationProfile;
  private volatile Map<String, RSAKey> keys = Map.of();
  private volatile Instant loadedAt;

  public OboAuthorizer(OboProperties properties, JwksLoader loader, Clock clock) {
    this(properties, loader, clock, false);
  }

  public OboAuthorizer(
      OboProperties properties, JwksLoader loader, Clock clock, boolean evaluationProfile) {
    this.properties = properties;
    this.loader = loader;
    this.clock = clock;
    this.evaluationProfile = evaluationProfile;
  }

  public OboPrincipal authorize(String serialized, AuthorizationRequest request) {
    try {
      SignedJWT jwt = SignedJWT.parse(serialized);
      require(JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()), "Wrong algorithm");
      String kid = jwt.getHeader().getKeyID();
      boolean refreshed = false;
      Instant now = clock.instant();
      if (loadedAt == null
          || Duration.between(loadedAt, now).compareTo(properties.jwksCacheTtl()) >= 0) {
        refresh();
        refreshed = true;
      }
      RSAKey key = keys.get(kid);
      if (key == null && !refreshed) {
        refresh();
        key = keys.get(kid);
      }
      require(key != null, "Unknown signing key");
      require(jwt.verify(new RSASSAVerifier(key.toRSAPublicKey())), "Invalid signature");
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      validateClaims(claims, request);
      return new OboPrincipal(
          claims.getSubject(),
          claims.getClaimAsString("session"),
          claims.getClaimAsString("scope"),
          claims.getClaimAsString("sandbox"));
    } catch (ParseException | JOSEException | RuntimeException exception) {
      if (exception instanceof IdentityVerificationUnavailableException unavailableException) {
        throw unavailableException;
      }
      if (exception instanceof OboAuthorizationException authorizationException) {
        throw authorizationException;
      }
      throw new OboAuthorizationException("OBO authorization failed");
    }
  }

  private void validateClaims(JWTClaimsSet claims, AuthorizationRequest request)
      throws ParseException {
    require(properties.issuer().equals(claims.getIssuer()), "Wrong issuer");
    require(claims.getAudience().equals(List.of("commerce-service")), "Wrong commerce audience");
    require("agent_obo".equals(claims.getClaimAsString("token_type")), "Wrong token type");
    require(hasText(claims.getSubject()), "Missing subject");
    require(claims.getSubject().equals(claims.getClaimAsString("user_id")), "User mismatch");
    require(hasText(claims.getClaimAsString("session")), "Missing session");
    require(request.requiredScope().equals(claims.getClaimAsString("scope")), "Wrong scope");
    require(
        !request.requiredScope().contains(" ") && !request.requiredScope().contains("*"),
        "Scope must be exact");
    Map<String, Object> actor = claims.getJSONObjectClaim("act");
    require(actor != null && "agent-service".equals(actor.get("azp")), "Wrong actor");
    String sandboxId = claims.getClaimAsString("sandbox");
    if (sandboxId == null) {
      require(request.evalSandboxHeader() == null, "Production token cannot use Evaluation header");
    } else {
      require(evaluationProfile, "Evaluation context is not enabled");
      require(hasText(sandboxId), "Missing evaluation sandbox");
      require(sandboxId.equals(request.evalSandboxHeader()), "Evaluation sandbox mismatch");
    }
    require(claims.getClaim("eval_sandbox") == null, "Evaluation context is not enabled");
    require(hasText(claims.getJWTID()), "Missing token identifier");
    validateTime(claims);
    require(
        request.expectedSubject() == null || request.expectedSubject().equals(claims.getSubject()),
        "Resource owner mismatch");
    require(
        request.expectedSession() == null
            || request.expectedSession().equals(claims.getClaimAsString("session")),
        "Support session mismatch");
    require(
        request.bodySubject() == null || request.bodySubject().equals(claims.getSubject()),
        "Body identity substitution");
    require(
        request.bodySession() == null
            || request.bodySession().equals(claims.getClaimAsString("session")),
        "Body session substitution");
  }

  private void validateTime(JWTClaimsSet claims) {
    Instant now = clock.instant();
    Instant lower = now.minus(properties.clockSkew());
    Instant upper = now.plus(properties.clockSkew());
    Date expiration = claims.getExpirationTime();
    Date notBefore = claims.getNotBeforeTime();
    Date issuedAt = claims.getIssueTime();
    require(expiration != null && expiration.toInstant().isAfter(lower), "Expired token");
    require(notBefore != null && !notBefore.toInstant().isAfter(upper), "Premature token");
    require(issuedAt != null && !issuedAt.toInstant().isAfter(upper), "Future issued-at");
  }

  private synchronized void refresh() {
    try {
      JWKSet set = JWKSet.parse(loader.load());
      Map<String, RSAKey> loaded = new HashMap<>();
      for (JWK key : set.getKeys()) {
        if (key instanceof RSAKey rsaKey
            && !key.isPrivate()
            && JWSAlgorithm.RS256.equals(key.getAlgorithm())
            && hasText(key.getKeyID())) {
          loaded.put(key.getKeyID(), rsaKey.toPublicJWK());
        }
      }
      keys = Map.copyOf(loaded);
      loadedAt = clock.instant();
    } catch (ParseException | RuntimeException exception) {
      throw new IdentityVerificationUnavailableException(exception);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new OboAuthorizationException(message);
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public record AuthorizationRequest(
      String requiredScope,
      String expectedSubject,
      String expectedSession,
      String bodySubject,
      String bodySession,
      String evalSandboxHeader) {}

  public record OboPrincipal(String subject, String sessionId, String scope, String sandboxId) {}
}
