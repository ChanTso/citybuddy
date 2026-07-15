package io.citybuddy.commerce.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OboAuthorizerTest {
  private static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");

  private RSAKey current;
  private RSAKey overlap;
  private AtomicInteger loads;
  private OboAuthorizer authorizer;

  @BeforeEach
  void setUp() throws Exception {
    current = key("current-key");
    overlap = key("overlap-key");
    loads = new AtomicInteger();
    authorizer = authorizer(List.of(current.toPublicJWK(), overlap.toPublicJWK()), loads);
  }

  @Test
  void authorizesExactOboAndAcceptsOverlappingPublicKey() throws Exception {
    OboAuthorizer.AuthorizationRequest request = request(null, null);

    OboAuthorizer.OboPrincipal principal =
        authorizer.authorize(token(overlap, TokenValues.valid()), request);

    assertThat(principal.subject()).isEqualTo("user-123");
    assertThat(principal.sessionId()).isEqualTo("session-123");
    assertThat(principal.scope()).isEqualTo("catalog:read");
    assertThat(loads).hasValue(1);
    authorizer.authorize(token(current, TokenValues.valid()), request);
    assertThat(loads).hasValue(1);
  }

  @Test
  void rejectsEveryIdentityDimensionAndBodySubstitution() throws Exception {
    assertRejected(TokenValues.valid().withIssuer("https://wrong.example"), request(null, null));
    assertRejected(TokenValues.valid().withAudience("citybuddy-web"), request(null, null));
    assertRejected(TokenValues.valid().withTokenType("direct_user"), request(null, null));
    assertRejected(TokenValues.valid().withScope("catalog:write"), request(null, null));
    assertThatThrownBy(
            () ->
                authorizer.authorize(
                    token(current, TokenValues.valid().withScope("catalog:*")),
                    new OboAuthorizer.AuthorizationRequest(
                        "catalog:*", "user-123", "session-123", null, null, null)))
        .isInstanceOf(OboAuthorizationException.class)
        .hasMessage("Scope must be exact");
    assertRejected(TokenValues.valid().withActor("other-service"), request(null, null));
    assertRejected(TokenValues.valid().withSubject("other-user"), request(null, null));
    assertRejected(TokenValues.valid().withSession("other-session"), request(null, null));
    assertRejected(TokenValues.valid().withExpiresAt(NOW.minusSeconds(60)), request(null, null));
    assertRejected(TokenValues.valid().withNotBefore(NOW.plusSeconds(60)), request(null, null));
    assertRejected(TokenValues.valid(), request("other-user", null));
    assertRejected(TokenValues.valid(), request(null, "other-session"));
  }

  @Test
  void refreshesOnlyOnceForContinuedUnknownKid() throws Exception {
    RSAKey unknown = key("unknown-key");

    assertThatThrownBy(
            () -> authorizer.authorize(token(unknown, TokenValues.valid()), request(null, null)))
        .isInstanceOf(OboAuthorizationException.class)
        .hasMessage("Unknown signing key");
    assertThat(loads).hasValue(1);
  }

  @Test
  void rejectsEvaluationHeaderClaimMissingJtiAndFutureIssuedAt() throws Exception {
    assertThatThrownBy(
            () ->
                authorizer.authorize(
                    token(current, TokenValues.valid()),
                    new OboAuthorizer.AuthorizationRequest(
                        "catalog:read",
                        "user-123",
                        "session-123",
                        null,
                        null,
                        "forbidden-production-context")))
        .isInstanceOf(OboAuthorizationException.class)
        .hasMessageContaining("Evaluation");
    assertThatThrownBy(
            () ->
                authorizer.authorize(
                    token(current, TokenValues.valid(), "token-123", NOW, Map.of("sandbox", "x")),
                    request(null, null)))
        .isInstanceOf(OboAuthorizationException.class)
        .hasMessageContaining("Evaluation");
    assertThatThrownBy(
            () ->
                authorizer.authorize(
                    token(current, TokenValues.valid(), null, NOW, Map.of()), request(null, null)))
        .isInstanceOf(OboAuthorizationException.class)
        .hasMessage("Missing token identifier");
    assertThatThrownBy(
            () ->
                authorizer.authorize(
                    token(current, TokenValues.valid(), "token-123", NOW.plusSeconds(60), Map.of()),
                    request(null, null)))
        .isInstanceOf(OboAuthorizationException.class)
        .hasMessage("Future issued-at");
  }

  @Test
  void expiresRetiredKnownKeyAfterBoundedCache() throws Exception {
    AtomicReference<String> jwks =
        new AtomicReference<>(new JWKSet(overlap.toPublicJWK()).toString());
    OboAuthorizer immediateRefresh =
        new OboAuthorizer(
            new OboProperties(
                "https://identity.citybuddy.test",
                "https://auth.test/auth/jwks",
                Duration.ofSeconds(30),
                Duration.ZERO),
            jwks::get,
            Clock.fixed(NOW, ZoneOffset.UTC));
    String signed = token(overlap, TokenValues.valid());

    assertThat(immediateRefresh.authorize(signed, request(null, null)).subject())
        .isEqualTo("user-123");
    jwks.set(new JWKSet().toString());

    assertThatThrownBy(() -> immediateRefresh.authorize(signed, request(null, null)))
        .isInstanceOf(OboAuthorizationException.class)
        .hasMessage("Unknown signing key");
  }

  private void assertRejected(
      TokenValues values, OboAuthorizer.AuthorizationRequest authorizationRequest)
      throws JOSEException {
    assertThatThrownBy(() -> authorizer.authorize(token(current, values), authorizationRequest))
        .isInstanceOf(OboAuthorizationException.class);
  }

  private OboAuthorizer.AuthorizationRequest request(String bodySubject, String bodySession) {
    return new OboAuthorizer.AuthorizationRequest(
        "catalog:read", "user-123", "session-123", bodySubject, bodySession, null);
  }

  private OboAuthorizer authorizer(List<RSAKey> keys, AtomicInteger counter) {
    String jwks = new JWKSet(keys.stream().map(key -> (JWK) key).toList()).toString();
    JwksLoader loader =
        () -> {
          counter.incrementAndGet();
          return jwks;
        };
    return new OboAuthorizer(
        new OboProperties(
            "https://identity.citybuddy.test",
            "https://auth.test/auth/jwks",
            Duration.ofSeconds(30),
            Duration.ofSeconds(60)),
        loader,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private String token(RSAKey key, TokenValues values) throws JOSEException {
    return token(key, values, "token-123", NOW, Map.of());
  }

  private String token(
      RSAKey key, TokenValues values, String jti, Instant issuedAt, Map<String, Object> extraClaims)
      throws JOSEException {
    JWTClaimsSet.Builder builder =
        new JWTClaimsSet.Builder()
            .issuer(values.issuer())
            .audience(values.audience())
            .subject(values.subject())
            .claim("user_id", values.userId())
            .claim("session", values.session())
            .claim("scope", values.scope())
            .claim("token_type", values.tokenType())
            .claim("act", Map.of("azp", values.actor()))
            .issueTime(Date.from(issuedAt))
            .notBeforeTime(Date.from(values.notBefore()))
            .expirationTime(Date.from(values.expiresAt()));
    if (jti != null) {
      builder.jwtID(jti);
    }
    extraClaims.forEach(builder::claim);
    JWTClaimsSet claims = builder.build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
    return jwt.serialize();
  }

  private RSAKey key(String kid) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
        .privateKey((RSAPrivateKey) pair.getPrivate())
        .keyID(kid)
        .algorithm(JWSAlgorithm.RS256)
        .build();
  }

  private record TokenValues(
      String issuer,
      String audience,
      String subject,
      String userId,
      String session,
      String scope,
      String tokenType,
      String actor,
      Instant notBefore,
      Instant expiresAt) {
    static TokenValues valid() {
      return new TokenValues(
          "https://identity.citybuddy.test",
          "commerce-service",
          "user-123",
          "user-123",
          "session-123",
          "catalog:read",
          "agent_obo",
          "agent-service",
          NOW,
          NOW.plusSeconds(120));
    }

    TokenValues withIssuer(String value) {
      return new TokenValues(
          value, audience, subject, userId, session, scope, tokenType, actor, notBefore, expiresAt);
    }

    TokenValues withAudience(String value) {
      return new TokenValues(
          issuer, value, subject, userId, session, scope, tokenType, actor, notBefore, expiresAt);
    }

    TokenValues withSubject(String value) {
      return new TokenValues(
          issuer, audience, value, userId, session, scope, tokenType, actor, notBefore, expiresAt);
    }

    TokenValues withSession(String value) {
      return new TokenValues(
          issuer, audience, subject, userId, value, scope, tokenType, actor, notBefore, expiresAt);
    }

    TokenValues withScope(String value) {
      return new TokenValues(
          issuer, audience, subject, userId, session, value, tokenType, actor, notBefore,
          expiresAt);
    }

    TokenValues withTokenType(String value) {
      return new TokenValues(
          issuer, audience, subject, userId, session, scope, value, actor, notBefore, expiresAt);
    }

    TokenValues withActor(String value) {
      return new TokenValues(
          issuer, audience, subject, userId, session, scope, tokenType, value, notBefore,
          expiresAt);
    }

    TokenValues withNotBefore(Instant value) {
      return new TokenValues(
          issuer, audience, subject, userId, session, scope, tokenType, actor, value, expiresAt);
    }

    TokenValues withExpiresAt(Instant value) {
      return new TokenValues(
          issuer, audience, subject, userId, session, scope, tokenType, actor, notBefore, value);
    }
  }
}
