package io.citybuddy.commerce.merchant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.citybuddy.commerce.merchant.MerchantChangeModels.Command;
import io.citybuddy.commerce.merchant.MerchantChangeModels.View;
import io.citybuddy.commerce.merchant.MerchantModels.Context;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MerchantChangeControllerTest {
  private static final String CHANGES = "/internal/merchant/changes";
  private static final String APPLY = "/api/merchant/changes/change-1/apply";
  private static final Context CONTEXT = new Context("operator", "merchant-session");
  private final MerchantChangeService service = mock(MerchantChangeService.class);
  private final ObjectMapper mapper = new ObjectMapper();
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
            .keyID("merchant-change-test")
            .build();
    var obo =
        new OboAuthorizer(
            new OboProperties(
                "https://identity.citybuddy.test", "unused", Duration.ZERO, Duration.ofMinutes(1)),
            () -> new JWKSet(key.toPublicJWK()).toString(),
            Clock.systemUTC(),
            true);
    var direct =
        new DirectUserAuthorizer(
            "https://identity.citybuddy.test",
            "citybuddy-web",
            Duration.ofMinutes(1),
            Duration.ZERO,
            "catalog:read",
            () -> new JWKSet(key.toPublicJWK()).toString(),
            Clock.systemUTC(),
            true);
    mvc =
        MockMvcBuilders.standaloneSetup(new MerchantChangeController(obo, direct, service, mapper))
            .setControllerAdvice(new MerchantChangeExceptionHandler())
            .build();
  }

  @Test
  void prepareUsesTheSignedOperatorSessionAndUnmodifiedIdempotencyKey() throws Exception {
    var payload =
        mapper.readTree(
            "{\"currency\":\"CNY\",\"items\":[{\"productId\":\"sku\",\"newPriceMinor\":1299}]}");
    var command = new Command("PRICE_UPDATE", payload);
    when(service.prepare(CONTEXT, "change/a?1#x", command)).thenReturn(view("PREPARED"));
    mvc.perform(
            obo(post(CHANGES), "merchant:change:prepare")
                .header("Idempotency-Key", "change/a?1#x")
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(Map.of("kind", "PRICE_UPDATE", "payload", payload))))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.state").value("PREPARED"));
    verify(service).prepare(CONTEXT, "change/a?1#x", command);
  }

  @Test
  void allThreeReadPrepareCancelScopesAreEndpointExact() throws Exception {
    for (String scope :
        List.of(
            "merchant:read",
            "merchant:price:prepare",
            "merchant:change:read",
            "merchant:change:cancel")) {
      mvc.perform(obo(post(CHANGES), scope).content("not-json")).andExpect(status().isForbidden());
    }
    mvc.perform(obo(get(CHANGES), "merchant:change:prepare")).andExpect(status().isForbidden());
    mvc.perform(obo(post(CHANGES + "/change-1/cancel"), "merchant:change:read"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void wrongActorDirectIdentitySessionAndEvaluationCannotReachTheService() throws Exception {
    mvc.perform(post(CHANGES).content("bad")).andExpect(status().isForbidden());
    for (String actor : List.of("agent-service", "shopping-agent")) {
      mvc.perform(
              post(CHANGES)
                  .header(
                      "Authorization",
                      "Bearer "
                          + token(true, "merchant:change:prepare", actor, "merchant-session", null))
                  .header("X-Merchant-Session-Id", "merchant-session")
                  .content("bad"))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            post(CHANGES)
                .header(
                    "Authorization",
                    "Bearer " + token(false, "merchant:change:apply", null, null, null))
                .header("X-Merchant-Session-Id", "merchant-session")
                .content("bad"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(CHANGES)
                .header(
                    "Authorization",
                    "Bearer "
                        + token(true, "merchant:change:prepare", "merchant-agent", "other", null))
                .header("X-Merchant-Session-Id", "merchant-session")
                .content("bad"))
        .andExpect(status().isForbidden());
    mvc.perform(
            obo(post(CHANGES), "merchant:change:prepare")
                .header("X-Eval-Sandbox-Id", "sandbox")
                .content("bad"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(CHANGES)
                .header(
                    "Authorization",
                    "Bearer "
                        + token(
                            true,
                            "merchant:change:prepare",
                            "merchant-agent",
                            "merchant-session",
                            "sandbox"))
                .header("X-Merchant-Session-Id", "merchant-session")
                .content("bad"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void strictOuterBodyAndDuplicateJsonRejectBeforePreparingAnyChange() throws Exception {
    for (String body :
        List.of(
            "",
            "null",
            "[]",
            "{}",
            "{} {}",
            "{\"kind\":1,\"payload\":{}}",
            "{\"kind\":\"PRICE_UPDATE\",\"payload\":[]}",
            "{\"kind\":\"PRICE_UPDATE\",\"payload\":{},\"owner\":\"other\"}",
            "{\"kind\":\"PRICE_UPDATE\",\"kind\":\"LISTING_UPDATE\",\"payload\":{}}",
            "{\"kind\":\"PRICE_UPDATE\",\"payload\":{\"items\":[],\"items\":[]}}")) {
      mvc.perform(
              obo(post(CHANGES), "merchant:change:prepare")
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(obo(post(CHANGES), "merchant:change:prepare").content(" ".repeat(65_537)))
        .andExpect(status().isPayloadTooLarge());
    mvc.perform(
            obo(post(CHANGES), "merchant:change:prepare")
                .header("Idempotency-Key", "one", "two")
                .content("{\"kind\":\"PRICE_UPDATE\",\"payload\":{}}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void listingAndDetailUseOwnerContextAndBoundedSingleQueryValues() throws Exception {
    when(service.list(CONTEXT, "PREPARED", 100, 50)).thenReturn(List.of(view("PREPARED")));
    when(service.get(CONTEXT, "change-1")).thenReturn(view("PREPARED"));
    mvc.perform(
            obo(
                get(CHANGES).param("state", "PREPARED").param("limit", "100").param("offset", "50"),
                "merchant:change:read"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$[0].changeId").value("change-1"));
    mvc.perform(obo(get(CHANGES + "/change-1"), "merchant:change:read"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("ETag"));
    verify(service).list(CONTEXT, "PREPARED", 100, 50);
    verify(service).get(CONTEXT, "change-1");
  }

  @Test
  void malformedOrOwnerSuppliedListQueriesAreRejectedWithoutLoading() throws Exception {
    for (String value : List.of("-1", "1.5", "", "+1", "10001", "999999999999")) {
      mvc.perform(obo(get(CHANGES).param("offset", value), "merchant:change:read"))
          .andExpect(status().isBadRequest());
    }
    for (String value : List.of("0", "101")) {
      mvc.perform(obo(get(CHANGES).param("limit", value), "merchant:change:read"))
          .andExpect(status().isBadRequest());
    }
    for (String name : List.of("state", "limit", "offset")) {
      mvc.perform(obo(get(CHANGES).param(name, "1", "2"), "merchant:change:read"))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(obo(get(CHANGES).param("owner", "other"), "merchant:change:read"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void cancellationRequiresItsExactScopeAndCannotCarryReplacementPayload() throws Exception {
    when(service.cancel(CONTEXT, "change-1")).thenReturn(view("CANCELLED"));
    mvc.perform(obo(post(CHANGES + "/change-1/cancel"), "merchant:change:cancel").content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CANCELLED"));
    verify(service).cancel(CONTEXT, "change-1");
  }

  @Test
  void explicitApplyAcceptsOnlyTheSignedOperatorAndPreservesConflictTerminal() throws Exception {
    when(service.apply("operator", "change-1")).thenReturn(view("APPLIED"));
    mvc.perform(direct(post(APPLY), "merchant:change:apply").content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("APPLIED"));
    when(service.apply("operator", "change-1")).thenReturn(view("REJECTED"));
    mvc.perform(direct(post(APPLY), "merchant:change:apply"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.state").value("REJECTED"));
  }

  @Test
  void modelCredentialsOldPermissionAndEvaluationCannotApply() throws Exception {
    for (String value :
        List.of(
            token(true, "merchant:change:apply", "merchant-agent", "merchant-session", null),
            token(false, "merchant:price:apply", null, null, null),
            token(false, "merchant:change:apply", null, null, "sandbox"))) {
      mvc.perform(post(APPLY).header("Authorization", "Bearer " + value).content("bad"))
          .andExpect(status().is4xxClientError());
    }
    mvc.perform(direct(post(APPLY), "merchant:change:apply").header("X-Eval-Sandbox-Id", "sandbox"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test
  void confirmationAndCancellationBodiesCannotReplaceTheStoredProposal() throws Exception {
    for (String body : List.of("null", "[]", "{}{}", "{\"owner\":\"other\"}", "{\"payload\":{}}")) {
      mvc.perform(direct(post(APPLY), "merchant:change:apply").content(body))
          .andExpect(status().isBadRequest());
      mvc.perform(obo(post(CHANGES + "/change-1/cancel"), "merchant:change:cancel").content(body))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(service);
  }

  @Test
  void domainRejectionRemainsAnExplicitSafeFailure() throws Exception {
    when(service.prepare(eq(CONTEXT), eq("one"), any()))
        .thenThrow(new MerchantException(422, "NOT_EDITABLE", "Product cannot be changed"));
    mvc.perform(
            obo(post(CHANGES), "merchant:change:prepare")
                .header("Idempotency-Key", "one")
                .content("{\"kind\":\"INVENTORY_ACTION\",\"payload\":{}}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.category").value("NOT_EDITABLE"));
  }

  private View view(String state) {
    return new View(
        "change-1",
        "PRICE_UPDATE",
        state,
        "CNY",
        mapper.createArrayNode(),
        mapper.createObjectNode(),
        null,
        Instant.parse("2026-09-07T00:00:00Z"),
        null);
  }

  private MockHttpServletRequestBuilder obo(MockHttpServletRequestBuilder request, String scope)
      throws Exception {
    return request
        .header(
            "Authorization",
            "Bearer " + token(true, scope, "merchant-agent", "merchant-session", null))
        .header("X-Merchant-Session-Id", "merchant-session");
  }

  private MockHttpServletRequestBuilder direct(
      MockHttpServletRequestBuilder request, String permission) throws Exception {
    return request.header("Authorization", "Bearer " + token(false, permission, null, null, null));
  }

  private String token(boolean obo, String permission, String actor, String session, String sandbox)
      throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience(obo ? "commerce-service" : "citybuddy-web")
            .subject("operator")
            .claim(
                "token_type",
                obo ? "agent_obo" : sandbox == null ? "direct_user" : "eval_direct_user")
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(120)))
            .jwtID(UUID.randomUUID().toString());
    if (obo) {
      claims
          .claim("user_id", "operator")
          .claim("session", session)
          .claim("scope", permission)
          .claim("act", Map.of("azp", actor));
    } else {
      claims.claim("principal_state", "ACTIVE").claim("permissions", List.of(permission));
    }
    if (sandbox != null) {
      claims.claim("sandbox", sandbox);
    }
    var signed =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            claims.build());
    signed.sign(new RSASSASigner(key.toRSAPrivateKey()));
    return signed.serialize();
  }
}
