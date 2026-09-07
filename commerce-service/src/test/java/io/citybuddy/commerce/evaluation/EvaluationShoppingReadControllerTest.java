package io.citybuddy.commerce.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.identity.OboProperties;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EvaluationShoppingReadControllerTest {
  private static final String ISSUER = "https://identity.citybuddy.test";
  private static final String PATH = "/internal/eval/shopping/";
  private final EvaluationShoppingReadService service = mock(EvaluationShoppingReadService.class);
  private final EvaluationSandboxAccess access = mock(EvaluationSandboxAccess.class);
  private RSAKey key;
  private MockMvc mvc;

  @BeforeEach
  void setup() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var pair = generator.generateKeyPair();
    key =
        new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .privateKey((RSAPrivateKey) pair.getPrivate())
            .algorithm(JWSAlgorithm.RS256)
            .keyID("eval-shopping-test")
            .build();
    var obo =
        new OboAuthorizer(
            new OboProperties(ISSUER, "unused", null, null),
            () -> new JWKSet(key.toPublicJWK()).toString(),
            Clock.systemUTC(),
            true);
    var direct =
        new DirectUserAuthorizer(
            ISSUER,
            "citybuddy-user",
            Duration.ofMinutes(5),
            Duration.ZERO,
            "support:chat",
            () -> new JWKSet(key.toPublicJWK()).toString(),
            Clock.systemUTC(),
            true);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new EvaluationShoppingReadController(obo, direct, access, service))
            .setControllerAdvice(new EvaluationShoppingReadExceptionHandler())
            .build();
  }

  @Test
  void usesSignedOwnerAndSandboxAndChecksLivenessBeforeReading() throws Exception {
    when(service.list("buyer", "sandbox", 20)).thenReturn(List.of());
    when(service.find("buyer", "sandbox", "missing")).thenReturn(Optional.empty());
    String token = signed(oboClaims("shopping:orders:read"));
    mvc.perform(request("orders", token))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    verify(service).list("buyer", "sandbox", 20);
    verify(access).requireActive("sandbox");
    mvc.perform(request("orders/missing", token))
        .andExpect(status().isNotFound())
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void refusesWrongActorScopeSessionSandboxAndExpiredTokens() throws Exception {
    for (var claims :
        List.of(
            oboClaims("shopping:orders:read").claim("act", Map.of("azp", "agent-service")),
            oboClaims("shopping:orders:read").claim("act", Map.of("azp", "merchant-agent")),
            oboClaims("shopping:cart:read"),
            oboClaims("shopping:orders:read").claim("scope", "shopping:orders:read refund:create"),
            oboClaims("shopping:orders:read").claim("session", "other-session"),
            oboClaims("shopping:orders:read").claim("sandbox", "other-sandbox"),
            oboClaims("shopping:orders:read").claim("sandbox", null),
            oboClaims("shopping:orders:read").expirationTime(Date.from(Instant.EPOCH)))) {
      mvc.perform(request("orders", signed(claims))).andExpect(status().isForbidden());
    }
    verifyNoInteractions(access, service);
  }

  @Test
  void refusesMissingOrAmbiguousIdentityHeaders() throws Exception {
    String token = signed(oboClaims("shopping:orders:read"));
    for (String omitted : List.of("Authorization", "X-Shopping-Session-Id", "X-Eval-Sandbox-Id")) {
      var request = get(PATH + "orders");
      Map.of(
              "Authorization", "Bearer " + token,
              "X-Shopping-Session-Id", "shop-session",
              "X-Eval-Sandbox-Id", "sandbox")
          .forEach(
              (name, value) -> {
                if (!name.equals(omitted)) {
                  request.header(name, value);
                }
              });
      mvc.perform(request).andExpect(status().isForbidden());
    }
    for (String duplicated :
        List.of("Authorization", "X-Shopping-Session-Id", "X-Eval-Sandbox-Id")) {
      mvc.perform(request("orders", token).header(duplicated, "another-value"))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(access, service);
  }

  @Test
  void refusesOwnerSubstitutionAndUnboundedOrRepeatedQueryParameters() throws Exception {
    String token = signed(oboClaims("shopping:orders:read"));
    for (String limit : List.of("0", "51", "-1", "1.5", "", "999999999999999999")) {
      mvc.perform(request("orders", token).param("limit", limit))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(request("orders", token).param("limit", "1", "2"))
        .andExpect(status().isBadRequest());
    mvc.perform(request("orders", token).param("userSubject", "victim"))
        .andExpect(status().isBadRequest());
    mvc.perform(request("orders/missing", token).param("sandbox", "other"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void completeSandboxCannotReadEvenWithAnUnexpiredToken() throws Exception {
    doThrow(
            new EvaluationSandboxException(
                403,
                EvaluationRejectionReason.ACCESS_SANDBOX_NOT_ACTIVE,
                "Evaluation sandbox is inactive"))
        .when(access)
        .requireActive("sandbox");
    mvc.perform(request("cart", signed(oboClaims("shopping:cart:read"))))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
    verifyNoInteractions(service);
  }

  @Test
  void profileAndCartUseTheirOwnScopes() throws Exception {
    mvc.perform(request("preferences", signed(oboClaims("shopping:profile:read"))))
        .andExpect(status().isOk());
    verify(service).preferences("buyer");
    mvc.perform(request("cart", signed(oboClaims("shopping:cart:read"))))
        .andExpect(status().isOk());
    verify(service).cart("buyer");
    mvc.perform(request("cart", signed(oboClaims("shopping:profile:read"))))
        .andExpect(status().isForbidden());
    mvc.perform(request("preferences", signed(oboClaims("shopping:cart:read"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void policiesRequireHandleBoundDirectEvaluationIdentity() throws Exception {
    when(service.policies("refund")).thenReturn(List.of());
    mvc.perform(request("policies", signed(directClaims())).param("query", "refund"))
        .andExpect(status().isOk());
    verify(service).policies("refund");
    for (var claims :
        List.of(
            directClaims().claim("evaluation_handle", null),
            directClaims().claim("sandbox", "other"),
            directClaims().claim("permissions", List.of("catalog:read")),
            directClaims()
                .claim("token_type", "direct_user")
                .claim("sandbox", null)
                .claim("evaluation_handle", null))) {
      assertThat(
              mvc.perform(request("policies", signed(claims)).param("query", "refund"))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isIn(401, 403);
    }
    mvc.perform(request("policies", signed(directClaims())).param("query", "a", "b"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void endpointsAreAbsentOutsideEvaluationOrWithoutObo() {
    for (String[] properties :
        List.of(
            new String[] {"citybuddy.obo.enabled=true"},
            new String[] {"spring.profiles.active=evaluation", "citybuddy.obo.enabled=false"})) {
      new ApplicationContextRunner()
          .withUserConfiguration(
              EvaluationShoppingReadConfiguration.class, EvaluationShoppingReadController.class)
          .withPropertyValues(properties)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(EvaluationShoppingReadController.class);
                assertThat(context).doesNotHaveBean(EvaluationShoppingReadService.class);
              });
    }
  }

  private static MockHttpServletRequestBuilder request(String path, String token) {
    return get(PATH + path)
        .header("Authorization", "Bearer " + token)
        .header("X-Eval-Sandbox-Id", "sandbox")
        .header("X-Shopping-Session-Id", "shop-session");
  }

  private static JWTClaimsSet.Builder oboClaims(String scope) {
    return commonClaims()
        .audience("commerce-service")
        .claim("token_type", "agent_obo")
        .claim("user_id", "buyer")
        .claim("scope", scope)
        .claim("session", "shop-session")
        .claim("act", Map.of("azp", "shopping-agent"));
  }

  private static JWTClaimsSet.Builder directClaims() {
    return commonClaims()
        .audience("citybuddy-user")
        .claim("token_type", "eval_direct_user")
        .claim("evaluation_handle", "h".repeat(43))
        .claim("principal_state", "ACTIVE")
        .claim("permissions", List.of("shopping:session:create"));
  }

  private static JWTClaimsSet.Builder commonClaims() {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .subject("buyer")
        .jwtID(UUID.randomUUID().toString())
        .issueTime(Date.from(now.minusSeconds(1)))
        .notBeforeTime(Date.from(now.minusSeconds(1)))
        .expirationTime(Date.from(now.plusSeconds(90)))
        .claim("sandbox", "sandbox");
  }

  private String signed(JWTClaimsSet.Builder claims) throws Exception {
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            claims.build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }
}
