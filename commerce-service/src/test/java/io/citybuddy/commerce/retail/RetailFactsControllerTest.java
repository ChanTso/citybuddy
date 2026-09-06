package io.citybuddy.commerce.retail;

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
import io.citybuddy.commerce.catalog.CatalogExceptionHandler;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.DeliveryEstimate;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.EstimateItem;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.EstimateRequest;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.QuotedItem;
import io.citybuddy.commerce.retail.RetailPolicyModels.Policy;
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

class RetailFactsControllerTest {
  private final RetailPolicyRepository policies = mock(RetailPolicyRepository.class);
  private final RetailFulfillmentService fulfillment = mock(RetailFulfillmentService.class);
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
            .keyID("facts-test")
            .build();
    var authorizer =
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
        MockMvcBuilders.standaloneSetup(
                new RetailFactsController(authorizer, policies, fulfillment, new ObjectMapper()))
            .setControllerAdvice(new RetailFactsExceptionHandler(), new CatalogExceptionHandler())
            .build();
  }

  @Test
  void signedDirectOwnerProvidesMembershipAndPublishedPoliciesReturnNoStore() throws Exception {
    Instant now = Instant.parse("2026-09-05T00:00:00Z");
    when(policies.search("returns & warranty"))
        .thenReturn(
            List.of(
                new Policy("retail-policy-returns", "Returns", null, "Published policy", 2, now)));
    mvc.perform(authorized(get("/api/retail/policies").queryParam("query", "returns & warranty")))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$[0].publicationVersion").value(2));
    var input = new EstimateRequest(List.of(new EstimateItem("sku", 2)));
    when(fulfillment.estimate("buyer", input))
        .thenReturn(
            new DeliveryEstimate(
                now,
                1,
                "CNY",
                "Asia/Shanghai",
                6000,
                List.of(new QuotedItem("sku", 2, 3000, 4)),
                true,
                List.of()));
    mvc.perform(
            authorized(post("/api/retail/fulfillment-options"))
                .contentType("application/json")
                .content("{\"items\":[{\"productId\":\"sku\",\"quantity\":2}]}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().doesNotExist("ETag"))
        .andExpect(jsonPath("$.estimateOnly").value(true))
        .andExpect(jsonPath("$.itemSubtotalMinor").value(6000));
    verify(fulfillment).estimate("buyer", input);
  }

  @Test
  void rejectsMissingWrongAndEvaluationIdentityBeforeParsing() throws Exception {
    mvc.perform(
            post("/api/retail/fulfillment-options").contentType("application/json").content("bad"))
        .andExpect(status().isUnauthorized());
    for (String value :
        List.of(
            token("direct_user", List.of("order:create"), null, null),
            token("agent_obo", List.of("catalog:read"), null, "shopping-agent"),
            token("eval_direct_user", List.of("catalog:read"), "sandbox", null))) {
      mvc.perform(
              post("/api/retail/fulfillment-options")
                  .header("Authorization", "Bearer " + value)
                  .contentType("application/json")
                  .content("bad"))
          .andExpect(status().is4xxClientError());
    }
    mvc.perform(
            authorized(get("/api/retail/policies").queryParam("query", "returns"))
                .header("X-Eval-Sandbox-Id", "sandbox"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(policies, fulfillment);
  }

  @Test
  void rejectsAmbiguousQueryAndStrictBodyTypesWithoutUsingPartialQuotes() throws Exception {
    mvc.perform(authorized(get("/api/retail/policies"))).andExpect(status().isBadRequest());
    mvc.perform(authorized(get("/api/retail/policies").queryParam("query", "a", "b")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            authorized(
                get("/api/retail/policies").queryParam("query", "a").queryParam("owner", "other")))
        .andExpect(status().isBadRequest());
    for (String body :
        List.of(
            "null",
            "[]",
            "{}",
            "{\"items\":null}",
            "{\"items\":[]}{}",
            "{\"items\":[],\"items\":[]}",
            "{\"items\":[],\"owner\":\"other\"}",
            "{\"items\":[{\"productId\":\"sku\",\"quantity\":\"1\"}]}",
            "{\"items\":[{\"productId\":\"sku\",\"quantity\":1.0}]}",
            "{\"items\":[{\"productId\":\"sku\",\"quantity\":2147483648}]}",
            "{\"items\":[{\"productId\":1,\"quantity\":1}]}",
            "{\"items\":[{\"productId\":\"sku\",\"quantity\":1,\"unitPriceMinor\":1}]}",
            "{\"items\":[{\"productId\":\"sku\",\"quantity\":0}]}",
            "{\"items\":[{\"productId\":\"sku\",\"quantity\":25}]}",
            "{\"items\":["
                + String.join(
                    ",",
                    java.util.Collections.nCopies(101, "{\"productId\":\"sku\",\"quantity\":1}"))
                + "]}")) {
      mvc.perform(
              authorized(post("/api/retail/fulfillment-options"))
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            authorized(post("/api/retail/fulfillment-options"))
                .contentType("application/json")
                .content(" ".repeat(16385)))
        .andExpect(status().isPayloadTooLarge());
    verifyNoInteractions(policies, fulfillment);
  }

  @Test
  void emptyItemsQuotesGeneralRulesAndUnquotableSkuIsAnExplicitBusinessFailure() throws Exception {
    var empty = new EstimateRequest(List.of());
    when(fulfillment.estimate("buyer", empty))
        .thenReturn(
            new DeliveryEstimate(
                Instant.now(), 1, "CNY", "Asia/Shanghai", 0, List.of(), true, List.of()));
    mvc.perform(
            authorized(post("/api/retail/fulfillment-options"))
                .contentType("application/json")
                .content("{\"items\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.itemSubtotalMinor").value(0));
    when(fulfillment.estimate("buyer", new EstimateRequest(List.of(new EstimateItem("family", 1)))))
        .thenThrow(new RetailFulfillmentException("sku_unavailable", "Choose an available SKU"));
    mvc.perform(
            authorized(post("/api/retail/fulfillment-options"))
                .contentType("application/json")
                .content("{\"items\":[{\"productId\":\"family\",\"quantity\":1}]}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.category").value("sku_unavailable"));
  }

  private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request)
      throws Exception {
    return request.header(
        "Authorization", "Bearer " + token("direct_user", List.of("catalog:read"), null, null));
  }

  private String token(String type, List<String> permissions, String sandbox, String actor)
      throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience("citybuddy-web")
            .subject("buyer")
            .claim("principal_state", "ACTIVE")
            .claim("token_type", type)
            .claim("permissions", permissions)
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString());
    if (sandbox != null) {
      claims.claim("sandbox", sandbox);
    }
    if (actor != null) {
      claims.claim("act", Map.of("azp", actor));
    }
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            claims.build());
    jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
    return jwt.serialize();
  }
}
