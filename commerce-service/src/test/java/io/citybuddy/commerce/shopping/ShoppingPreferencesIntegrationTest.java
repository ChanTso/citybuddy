package io.citybuddy.commerce.shopping;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.citybuddy.commerce.identity.OboIdentityConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(
    classes = ShoppingPreferencesIntegrationTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShoppingPreferencesIntegrationTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({
    ShoppingPreferencesConfiguration.class,
    ShoppingPreferencesController.class,
    ShoppingPreferencesExceptionHandler.class,
    OboIdentityConfiguration.class
  })
  static class Application {}

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("citybuddy.catalog.enabled", () -> "true");
    registry.add("citybuddy.obo.enabled", () -> "true");
    registry.add("citybuddy.obo.issuer", () -> "https://identity.citybuddy.test");
    registry.add("citybuddy.obo.jwks-url", () -> required("IDENTITY_JWKS_URL"));
  }

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  private String owner;
  private String session;
  private RSAPrivateKey key;

  @BeforeEach
  void prepare() throws Exception {
    owner = "retail-profile-" + UUID.randomUUID();
    session = "shop-" + UUID.randomUUID();
    String pem = Files.readString(Path.of(required("CATALOG_TEST_SIGNING_PRIVATE_KEY_PATH")));
    String encoded =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    key =
        (RSAPrivateKey)
            KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
  }

  @AfterEach
  void cleanup() throws Exception {
    try (var connection =
        DriverManager.getConnection(
            required("CATALOG_MYSQL_URL"),
            "bootstrap_admin",
            required("MYSQL_BOOTSTRAP_PASSWORD"))) {
      var fixture = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      fixture.execute("SET ROLE 'bootstrap_grant_role'");
      for (String subject : List.of(owner, owner.toUpperCase(java.util.Locale.ROOT))) {
        fixture.update("DELETE FROM crm_profile WHERE user_subject = BINARY ?", subject);
      }
    }
  }

  @Test
  void caseDistinctSubjectsOwnTheirProfilesAndReadsDoNotChangePersistence() throws Exception {
    String other = owner.toUpperCase(java.util.Locale.ROOT);
    jdbc.update(
        "INSERT INTO crm_profile(user_subject,display_name,contact_email,loyalty_tier,default_location,preferences) VALUES (?,?,?,?,?,?)",
        owner,
        "First buyer",
        "private@example.invalid",
        "MEMBER",
        "上海市徐汇区",
        "{\"style\":\"neutral\"}");
    jdbc.update(
        "INSERT INTO crm_profile(user_subject,display_name,loyalty_tier,default_location,preferences) VALUES (?,?,?,?,?)",
        other,
        "Second buyer",
        "NONE",
        "上海市浦东新区",
        "{\"style\":\"bright\"}");
    var before =
        jdbc.queryForList(
            "SELECT * FROM crm_profile WHERE user_subject IN (?,?) ORDER BY user_subject",
            owner,
            other);
    ResponseEntity<JsonNode> first = get(owner);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(first.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(first.getHeaders().getETag()).isNull();
    assertThat(first.getBody().path("userId").asText()).isEqualTo(owner);
    assertThat(first.getBody().path("loyaltyTier").asText()).isEqualTo("MEMBER");
    assertThat(first.getBody().path("preferences").path("style").asText()).isEqualTo("neutral");
    assertThat(first.getBody().has("contactEmail")).isFalse();
    assertThat(get(other).getBody().path("preferences").path("style").asText()).isEqualTo("bright");
    assertThat(
            jdbc.queryForList(
                "SELECT * FROM crm_profile WHERE user_subject IN (?,?) ORDER BY user_subject",
                owner,
                other))
        .isEqualTo(before);
  }

  @Test
  void absentProfileReturnsActualIdentityWithoutInsertingAGuest() throws Exception {
    ResponseEntity<JsonNode> result = get(owner);
    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode profile = result.getBody();
    assertThat(profile.path("userId").asText()).isEqualTo(owner);
    assertThat(profile.path("displayName").isNull()).isTrue();
    assertThat(profile.path("defaultLocation").isNull()).isTrue();
    assertThat(profile.path("loyaltyTier").asText()).isEqualTo("NONE");
    assertThat(profile.path("preferences").size()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_profile WHERE user_subject = ?", Integer.class, owner))
        .isZero();
  }

  private ResponseEntity<JsonNode> get(String subject) throws Exception {
    Instant now = Instant.now();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("catalog-current").build(),
            new JWTClaimsSet.Builder()
                .issuer("https://identity.citybuddy.test")
                .audience("commerce-service")
                .subject(subject)
                .claim("user_id", subject)
                .claim("session", session)
                .claim("scope", "shopping:profile:read")
                .claim("token_type", "agent_obo")
                .claim("act", Map.of("azp", "shopping-agent"))
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString())
                .build());
    jwt.sign(new RSASSASigner(key));
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwt.serialize());
    headers.set("X-Shopping-Session-Id", session);
    headers.set("If-None-Match", "\"prior\"");
    return http.exchange(
        "/internal/shopping/preferences",
        HttpMethod.GET,
        new HttpEntity<>(headers),
        JsonNode.class);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing " + name);
    }
    return value;
  }
}
