package io.citybuddy.commerce.cart;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.citybuddy.commerce.cart.CartModels.CartView;
import io.citybuddy.commerce.cart.CartModels.Result;
import io.citybuddy.commerce.identity.IdentityVerificationUnavailableException;
import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.identity.SupportSessionId;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@ConditionalOnProperty(
    name = {"citybuddy.orders.enabled", "citybuddy.obo.enabled"},
    havingValue = "true")
public final class CartController {
  private static final int MAXIMUM_BODY_BYTES = 8192;
  private static final Pattern VERSION_ETAG = Pattern.compile("\"(0|[1-9][0-9]*)\"");
  private final OboAuthorizer obo;
  private final CartService service;
  private final ObjectReader reader;

  public CartController(OboAuthorizer obo, CartService service, ObjectMapper mapper) {
    this.obo = obo;
    this.service = service;
    reader =
        mapper
            .readerFor(JsonNode.class)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  @GetMapping("/internal/shopping/cart")
  public ResponseEntity<CartView> get(HttpServletRequest request) {
    CartView cart = service.get(owner(request, "shopping:cart:read"));
    return ResponseEntity.ok().eTag(Long.toString(cart.version())).body(cart);
  }

  @PostMapping(value = "/internal/shopping/cart/items", consumes = "application/json")
  public Result add(HttpServletRequest request) {
    String owner = owner(request, "shopping:cart:write");
    JsonNode body = body(request, Set.of("productId", "quantity"));
    JsonNode productId = body.get("productId");
    if (productId == null || !productId.isTextual()) {
      throw invalid();
    }
    return service.add(
        owner, request.getHeader("Idempotency-Key"), productId.textValue(), quantity(body));
  }

  @PutMapping(value = "/internal/shopping/cart/items/{productId}", consumes = "application/json")
  public Result set(@PathVariable String productId, HttpServletRequest request) {
    String owner = owner(request, "shopping:cart:write");
    JsonNode body = body(request, Set.of("quantity", "expectedCartVersion"));
    JsonNode version = body.get("expectedCartVersion");
    if (version == null || !version.isIntegralNumber() || !version.canConvertToLong()) {
      throw invalid();
    }
    return service.set(
        owner,
        request.getHeader("Idempotency-Key"),
        productId,
        quantity(body),
        version.longValue());
  }

  @DeleteMapping("/internal/shopping/cart/items/{productId}")
  public Result remove(@PathVariable String productId, HttpServletRequest request) {
    String owner = owner(request, "shopping:cart:write");
    String etag = request.getHeader("If-Match");
    if (etag == null
        || Collections.list(request.getHeaders("If-Match")).size() != 1
        || !VERSION_ETAG.matcher(etag).matches()) {
      throw invalid();
    }
    long version;
    try {
      version = Long.parseLong(etag.substring(1, etag.length() - 1));
    } catch (NumberFormatException exception) {
      throw invalid();
    }
    return service.remove(owner, request.getHeader("Idempotency-Key"), productId, version);
  }

  @GetMapping("/internal/shopping/cart/commands/{commandKey}")
  public ResponseEntity<Result> command(
      @PathVariable String commandKey, HttpServletRequest request) {
    String owner = owner(request, "shopping:cart:read");
    return ResponseEntity.ok(
        service
            .command(owner, commandKey)
            .orElseThrow(() -> new CartException(404, "NOT_FOUND", "Cart command not found")));
  }

  private String owner(HttpServletRequest request, String scope) {
    if (request.getHeader("X-Eval-Sandbox-Id") != null) {
      throw new OboAuthorizationException("Evaluation context is not supported");
    }
    String session = request.getHeader("X-Shopping-Session-Id");
    String authorization = request.getHeader("Authorization");
    if (!SupportSessionId.isValid(session)) {
      throw new OboAuthorizationException("Shopping session is required");
    }
    if (authorization == null
        || !authorization.startsWith("Bearer ")
        || authorization.length() > 16384) {
      throw new OboAuthorizationException("OBO bearer is required");
    }
    var principal =
        obo.authorize(
            authorization.substring(7),
            new OboAuthorizer.AuthorizationRequest(
                scope, null, session, null, null, null, "shopping-agent"));
    if (principal.sandboxId() != null || principal.subject().length() > 128) {
      throw new OboAuthorizationException("Shopping identity is invalid");
    }
    return principal.subject();
  }

  private JsonNode body(HttpServletRequest request, Set<String> fields) {
    try {
      byte[] bytes = request.getInputStream().readNBytes(MAXIMUM_BODY_BYTES + 1);
      if (bytes.length > MAXIMUM_BODY_BYTES) {
        throw new CartException(413, "VALIDATION", "Cart request is too large");
      }
      JsonNode body = reader.readValue(bytes);
      if (body == null || !body.isObject()) {
        throw invalid();
      }
      var names = body.fieldNames();
      while (names.hasNext()) {
        if (!fields.contains(names.next())) {
          throw invalid();
        }
      }
      return body;
    } catch (IOException exception) {
      throw invalid();
    }
  }

  private static int quantity(JsonNode body) {
    JsonNode quantity = body.get("quantity");
    if (quantity == null || !quantity.isIntegralNumber() || !quantity.canConvertToInt()) {
      throw invalid();
    }
    return quantity.intValue();
  }

  private static CartException invalid() {
    return new CartException(400, "VALIDATION", "Invalid cart request");
  }
}

@RestControllerAdvice(assignableTypes = CartController.class)
final class CartExceptionHandler {
  @ExceptionHandler(CartException.class)
  ResponseEntity<Map<String, String>> handle(CartException exception) {
    return ResponseEntity.status(exception.status())
        .body(Map.of("category", exception.category(), "message", exception.getMessage()));
  }

  @ExceptionHandler(OboAuthorizationException.class)
  ResponseEntity<Map<String, String>> forbidden() {
    return ResponseEntity.status(403)
        .body(Map.of("category", "AUTHORIZATION", "message", "Forbidden"));
  }

  @ExceptionHandler(IdentityVerificationUnavailableException.class)
  ResponseEntity<Map<String, String>> unavailable() {
    return ResponseEntity.status(503)
        .body(
            Map.of(
                "category",
                "IDENTITY_UNAVAILABLE",
                "message",
                "Identity verification unavailable"));
  }
}
