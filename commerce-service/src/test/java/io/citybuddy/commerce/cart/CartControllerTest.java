package io.citybuddy.commerce.cart;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import io.citybuddy.commerce.cart.CartModels.CartView;
import io.citybuddy.commerce.cart.CartModels.Item;
import io.citybuddy.commerce.cart.CartModels.Receipt;
import io.citybuddy.commerce.cart.CartModels.Result;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.identity.OboProperties;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CartControllerTest {
  private final CartService service = mock(CartService.class);
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
            .keyID("cart-test")
            .build();
    var authorizer =
        new OboAuthorizer(
            new OboProperties("https://identity.citybuddy.test", "unused", null, null),
            () -> new JWKSet(key.toPublicJWK()).toString(),
            Clock.systemUTC(),
            true);
    mvc =
        MockMvcBuilders.standaloneSetup(new CartController(authorizer, service, new ObjectMapper()))
            .setControllerAdvice(new CartExceptionHandler())
            .build();
  }

  @Test
  void signedIdentityOwnsReadsAndVersionedWrites() throws Exception {
    CartView cart = new CartView(12, null, 0L, false, List.of());
    Result result = new Result(new Receipt("add-key", "ADD", "sku", 0, 2, 12), cart, false);
    when(service.get("buyer")).thenReturn(cart);
    when(service.add("buyer", "add-key", "sku", 2)).thenReturn(result);
    when(service.set("buyer", "set-key", "sku", 3, 12)).thenReturn(result);
    when(service.remove("buyer", "remove-key", "sku", 12)).thenReturn(result);
    mvc.perform(authorized(get("/internal/shopping/cart"), "shopping:cart:read"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().doesNotExist("ETag"));
    mvc.perform(
            authorized(post("/internal/shopping/cart/items"), "shopping:cart:write")
                .header("Idempotency-Key", "add-key")
                .contentType("application/json")
                .content("{\"productId\":\"sku\",\"quantity\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.receipt.afterQuantity").value(2));
    mvc.perform(
            authorized(put("/internal/shopping/cart/items/sku"), "shopping:cart:write")
                .header("Idempotency-Key", "set-key")
                .contentType("application/json")
                .content("{\"quantity\":3,\"expectedCartVersion\":12}"))
        .andExpect(status().isOk());
    mvc.perform(
            authorized(delete("/internal/shopping/cart/items/sku"), "shopping:cart:write")
                .header("Idempotency-Key", "remove-key")
                .queryParam("expectedCartVersion", "12"))
        .andExpect(status().isOk());
    verify(service).get("buyer");
    verify(service).add("buyer", "add-key", "sku", 2);
    verify(service).set("buyer", "set-key", "sku", 3, 12);
    verify(service).remove("buyer", "remove-key", "sku", 12);
  }

  @Test
  void readOnlyCommandRecoveryDoesNotMutateAndMissingCommandsAreClosedNotFound() throws Exception {
    Result receipt =
        new Result(
            new Receipt("done", "ADD", "sku", 0, 2, 1),
            new CartView(5, null, 0L, false, List.of()),
            true);
    when(service.command("buyer", "done")).thenReturn(Optional.of(receipt));
    when(service.command("buyer", "unknown")).thenReturn(Optional.empty());
    mvc.perform(
            authorized(
                get("/internal/shopping/cart/commands").queryParam("key", "done"),
                "shopping:cart:read"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.receipt.appliedVersion").value(1))
        .andExpect(jsonPath("$.cart.version").value(5));
    mvc.perform(
            authorized(
                get("/internal/shopping/cart/commands").queryParam("key", "unknown"),
                "shopping:cart:read"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    verify(service).command("buyer", "done");
    verify(service).command("buyer", "unknown");
    verifyNoMoreInteractions(service);
  }

  @Test
  void queryKeysPreserveSlashQuestionAndFragmentCharactersInReadOnlyRecovery() throws Exception {
    for (String commandKey :
        List.of("add/1", "add?variant=blue", "add#chosen", "add/1?variant#chosen")) {
      Result receipt =
          new Result(
              new Receipt(commandKey, "ADD", "sku", 0, 2, 1),
              new CartView(5, null, 0L, false, List.of()),
              true);
      when(service.command("buyer", commandKey)).thenReturn(Optional.of(receipt));
      mvc.perform(
              authorized(
                  get("/internal/shopping/cart/commands").queryParam("key", commandKey),
                  "shopping:cart:read"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.receipt.key").value(commandKey));
      verify(service).command("buyer", commandKey);
    }
    verifyNoMoreInteractions(service);
  }

  @Test
  void commandLookupRequiresExactlyOneKeyAndHasNoLegacyPathAlias() throws Exception {
    mvc.perform(authorized(get("/internal/shopping/cart/commands"), "shopping:cart:read"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            authorized(
                get("/internal/shopping/cart/commands").queryParam("key", "done", "other"),
                "shopping:cart:read"))
        .andExpect(status().isBadRequest());
    mvc.perform(authorized(get("/internal/shopping/cart/commands/done"), "shopping:cart:read"))
        .andExpect(status().isNotFound());
    verifyNoInteractions(service);
  }

  @Test
  void staleConditionalReadStillReturnsCurrentProductFactsAtTheSameCartVersion() throws Exception {
    CartView current =
        new CartView(
            12,
            "CNY",
            3000L,
            false,
            List.of(
                new Item(
                    "sku",
                    2,
                    "SKU",
                    1500,
                    "CNY",
                    8,
                    0,
                    true,
                    "PUBLISHED",
                    3000L,
                    false,
                    null,
                    Map.of(),
                    null)));
    when(service.get("buyer")).thenReturn(current);
    mvc.perform(
            authorized(get("/internal/shopping/cart"), "shopping:cart:read")
                .header("If-None-Match", "\"12\""))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().doesNotExist("ETag"))
        .andExpect(jsonPath("$.version").value(12))
        .andExpect(jsonPath("$.items[0].unitPriceMinor").value(1500))
        .andExpect(jsonPath("$.items[0].stockQuantity").value(0));
  }

  @Test
  void readAndWriteScopesAreExactAndCannotCrossActorsOrSessions() throws Exception {
    for (String token :
        List.of(
            token("agent-service", "shopping:cart:read", "shop-session", null, "agent_obo"),
            token("merchant-agent", "shopping:cart:read", "shop-session", null, "agent_obo"),
            token("shopping-agent", "shopping:cart:write", "shop-session", null, "agent_obo"),
            token("shopping-agent", "shopping:cart:*", "shop-session", null, "agent_obo"),
            token("shopping-agent", "shopping:cart:read", "other-session", null, "agent_obo"),
            token("shopping-agent", "shopping:cart:read", "shop-session", null, "direct_user"))) {
      mvc.perform(
              get("/internal/shopping/cart")
                  .header("Authorization", "Bearer " + token)
                  .header("X-Shopping-Session-Id", "shop-session"))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            authorized(post("/internal/shopping/cart/items"), "shopping:cart:read")
                .contentType("application/json")
                .content("{\"productId\":\"sku\",\"quantity\":1}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            authorized(delete("/internal/shopping/cart/items/sku"), "shopping:cart:read")
                .queryParam("expectedCartVersion", "0"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void rejectsEvaluationAndInvalidSessionEvenWhenVerifierEnablesEvaluation() throws Exception {
    mvc.perform(
            authorized(get("/internal/shopping/cart"), "shopping:cart:read")
                .header("X-Eval-Sandbox-Id", "sandbox"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/internal/shopping/cart")
                .header(
                    "Authorization",
                    "Bearer "
                        + token(
                            "shopping-agent",
                            "shopping:cart:read",
                            "shop-session",
                            "sandbox",
                            "agent_obo"))
                .header("X-Shopping-Session-Id", "shop-session"))
        .andExpect(status().isForbidden());
    for (String session : List.of("", "bad session", "x".repeat(65))) {
      mvc.perform(
              get("/internal/shopping/cart")
                  .header(
                      "Authorization",
                      "Bearer "
                          + token(
                              "shopping-agent",
                              "shopping:cart:read",
                              "shop-session",
                              null,
                              "agent_obo"))
                  .header("X-Shopping-Session-Id", session))
          .andExpect(status().isForbidden());
    }
    mvc.perform(get("/internal/shopping/cart")).andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void rejectsCoercedUnknownDuplicateAndUnboundedJsonBeforeMutation() throws Exception {
    for (String body :
        List.of(
            "null",
            "[]",
            "{}",
            "{\"productId\":7,\"quantity\":1}",
            "{\"productId\":\"sku\",\"quantity\":\"1\"}",
            "{\"productId\":\"sku\",\"quantity\":1.0}",
            "{\"productId\":\"sku\",\"quantity\":true}",
            "{\"productId\":\"sku\",\"quantity\":2147483648}",
            "{\"productId\":\"sku\",\"quantity\":1,\"quantity\":2}",
            "{\"productId\":\"sku\",\"quantity\":1,\"owner\":\"other\"}",
            "{\"productId\":\"sku\",\"quantity\":1} {}")) {
      mvc.perform(
              authorized(post("/internal/shopping/cart/items"), "shopping:cart:write")
                  .header("Idempotency-Key", "key")
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.category").value("VALIDATION"));
    }
    mvc.perform(
            authorized(post("/internal/shopping/cart/items"), "shopping:cart:write")
                .contentType("application/json")
                .content(" ".repeat(8193)))
        .andExpect(status().isPayloadTooLarge());
    for (String body :
        List.of(
            "{\"quantity\":1}",
            "{\"quantity\":1,\"expectedCartVersion\":\"12\"}",
            "{\"quantity\":1,\"expectedCartVersion\":12.0}",
            "{\"quantity\":1,\"expectedCartVersion\":9223372036854775808}",
            "{\"quantity\":1,\"expectedCartVersion\":12,\"productId\":\"other\"}")) {
      mvc.perform(
              authorized(put("/internal/shopping/cart/items/sku"), "shopping:cart:write")
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(service);
  }

  @Test
  void deleteRequiresExactlyOneNonnegativeLongQueryVersion() throws Exception {
    for (String version : List.of("", "+12", "*", "12,13", "-1", "1.5", "9223372036854775808")) {
      mvc.perform(
              authorized(delete("/internal/shopping/cart/items/sku"), "shopping:cart:write")
                  .queryParam("expectedCartVersion", version))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(authorized(delete("/internal/shopping/cart/items/sku"), "shopping:cart:write"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            authorized(delete("/internal/shopping/cart/items/sku"), "shopping:cart:write")
                .queryParam("expectedCartVersion", "12", "13"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            authorized(delete("/internal/shopping/cart/items/sku"), "shopping:cart:write")
                .header("If-Match", "\"12\""))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void reportsOnlyTheClosedBusinessError() throws Exception {
    when(service.remove("buyer", "key", "sku", 1))
        .thenThrow(new CartException(409, "VERSION_CONFLICT", "Cart version is stale"));
    mvc.perform(
            authorized(delete("/internal/shopping/cart/items/sku"), "shopping:cart:write")
                .header("Idempotency-Key", "key")
                .queryParam("expectedCartVersion", "1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.category").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.message").value("Cart version is stale"))
        .andExpect(jsonPath("$.owner").doesNotExist());
  }

  private MockHttpServletRequestBuilder authorized(
      MockHttpServletRequestBuilder request, String scope) throws Exception {
    return request
        .header(
            "Authorization",
            "Bearer " + token("shopping-agent", scope, "shop-session", null, "agent_obo"))
        .header("X-Shopping-Session-Id", "shop-session");
  }

  private String token(String actor, String scope, String session, String sandbox, String type)
      throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience("commerce-service")
            .subject("buyer")
            .claim("user_id", "buyer")
            .claim("session", session)
            .claim("scope", scope)
            .claim("token_type", type)
            .claim("act", Map.of("azp", actor))
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString());
    if (sandbox != null) {
      claims.claim("sandbox", sandbox);
    }
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            claims.build());
    jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
    return jwt.serialize();
  }
}
