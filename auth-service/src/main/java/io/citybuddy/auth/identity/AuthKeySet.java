package io.citybuddy.auth.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AuthKeySet {
  public static final String DIRECT_TYPE = "direct_user";
  public static final String OBO_TYPE = "agent_obo";

  private final IdentityProperties properties;
  private final Clock clock;
  private final RSAPrivateKey currentPrivateKey;
  private final Map<String, RSAPublicKey> publicKeys;

  public AuthKeySet(IdentityProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
    this.currentPrivateKey = readPrivate(properties.currentPrivateKeyPath());
    Map<String, RSAPublicKey> loaded = new LinkedHashMap<>();
    loaded.put(properties.currentKid(), readPublic(properties.currentPublicKeyPath()));
    if (hasText(properties.overlapKid()) && hasText(properties.overlapPublicKeyPath())) {
      loaded.put(properties.overlapKid(), readPublic(properties.overlapPublicKeyPath()));
    }
    this.publicKeys = Map.copyOf(loaded);
  }

  public String directToken(String subject, List<String> permissions) {
    Instant now = clock.instant();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(properties.issuer())
            .audience(properties.userAudience())
            .subject(subject)
            .claim("token_type", DIRECT_TYPE)
            .claim("principal_state", "ACTIVE")
            .claim("permissions", permissions)
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plus(properties.directTtl())))
            .jwtID(UUID.randomUUID().toString())
            .build();
    return sign(claims);
  }

  public String oboToken(String subject, String sessionId, String scope, String actor) {
    Instant now = clock.instant();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(properties.issuer())
            .audience("commerce-service")
            .subject(subject)
            .claim("user_id", subject)
            .claim("session", sessionId)
            .claim("scope", scope)
            .claim("token_type", OBO_TYPE)
            .claim("act", Map.of("azp", actor))
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plus(properties.oboTtl())))
            .jwtID(UUID.randomUUID().toString())
            .build();
    return sign(claims);
  }

  public DirectPrincipal validateDirect(
      String token, String requiredPermission, Set<String> acceptedKids) {
    JWTClaimsSet claims = verifiedClaims(token, acceptedKids);
    requireClaim(DIRECT_TYPE.equals(claims.getClaim("token_type")), "Wrong token type");
    requireClaim("ACTIVE".equals(claims.getClaim("principal_state")), "Inactive principal");
    requireClaim(properties.issuer().equals(claims.getIssuer()), "Wrong issuer");
    requireClaim(claims.getAudience().equals(List.of(properties.userAudience())), "Wrong audience");
    requireClaim(hasText(claims.getSubject()), "Missing subject");
    requireClaim(claims.getClaim("act") == null, "Direct token carries actor");
    requireClaim(claims.getClaim("session") == null, "Direct token carries session");
    requireClaim(claims.getClaim("sandbox") == null, "Evaluation token is not enabled");
    validateTime(claims);
    List<String> permissions;
    try {
      permissions = claims.getStringListClaim("permissions");
    } catch (ParseException exception) {
      throw new IdentityException(401, "Malformed permissions");
    }
    requireClaim(
        permissions != null && permissions.contains(requiredPermission), "Missing permission");
    return new DirectPrincipal(claims.getSubject(), List.copyOf(permissions));
  }

  public Map<String, Object> jwks(Set<String> publishedKids) {
    if (!publishedKids.contains(properties.currentKid())
        || !publicKeys.keySet().containsAll(publishedKids)) {
      throw new IllegalStateException("Published signing metadata has no runtime public key");
    }
    List<Map<String, Object>> keys =
        publicKeys.entrySet().stream()
            .filter(entry -> publishedKids.contains(entry.getKey()))
            .map(
                entry ->
                    new RSAKey.Builder(entry.getValue())
                        .keyID(entry.getKey())
                        .algorithm(JWSAlgorithm.RS256)
                        .build()
                        .toPublicJWK()
                        .toJSONObject())
            .toList();
    return Map.of("keys", keys);
  }

  private JWTClaimsSet verifiedClaims(String serialized, Set<String> acceptedKids) {
    try {
      SignedJWT jwt = SignedJWT.parse(serialized);
      requireClaim(JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()), "Wrong algorithm");
      String kid = jwt.getHeader().getKeyID();
      requireClaim(acceptedKids.contains(kid), "Unknown signing key");
      RSAPublicKey publicKey = publicKeys.get(kid);
      requireClaim(publicKey != null, "Unknown signing key");
      requireClaim(jwt.verify(new RSASSAVerifier(publicKey)), "Invalid signature");
      return jwt.getJWTClaimsSet();
    } catch (ParseException | JOSEException exception) {
      throw new IdentityException(401, "Invalid token");
    }
  }

  private void validateTime(JWTClaimsSet claims) {
    Instant now = clock.instant();
    Instant lower = now.minus(properties.clockSkew());
    Instant upper = now.plus(properties.clockSkew());
    requireClaim(claims.getExpirationTime() != null, "Missing expiration");
    requireClaim(claims.getNotBeforeTime() != null, "Missing not-before");
    requireClaim(claims.getIssueTime() != null, "Missing issued-at");
    requireClaim(claims.getExpirationTime().toInstant().isAfter(lower), "Expired token");
    requireClaim(!claims.getNotBeforeTime().toInstant().isAfter(upper), "Premature token");
    requireClaim(!claims.getIssueTime().toInstant().isAfter(upper), "Future issued-at");
  }

  private String sign(JWTClaimsSet claims) {
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(properties.currentKid()).build(),
              claims);
      jwt.sign(new RSASSASigner(currentPrivateKey));
      return jwt.serialize();
    } catch (JOSEException exception) {
      throw new IllegalStateException("Unable to sign token", exception);
    }
  }

  private static RSAPrivateKey readPrivate(String path) {
    try {
      byte[] bytes = decodePem(path, "PRIVATE KEY");
      return (RSAPrivateKey)
          KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to load current private signing key", exception);
    }
  }

  private static RSAPublicKey readPublic(String path) {
    try {
      byte[] bytes = decodePem(path, "PUBLIC KEY");
      return (RSAPublicKey)
          KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to load public signing key", exception);
    }
  }

  private static byte[] decodePem(String path, String type) throws IOException {
    String pem = Files.readString(Path.of(path), StandardCharsets.US_ASCII);
    String body =
        pem.replace("-----BEGIN " + type + "-----", "")
            .replace("-----END " + type + "-----", "")
            .replaceAll("\\s", "");
    return Base64.getDecoder().decode(body);
  }

  private static void requireClaim(boolean condition, String message) {
    if (!condition) {
      throw new IdentityException(401, message);
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public record DirectPrincipal(String subject, List<String> permissions) {}
}
