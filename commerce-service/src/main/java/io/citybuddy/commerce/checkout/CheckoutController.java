package io.citybuddy.commerce.checkout;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.citybuddy.commerce.catalog.CatalogException;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import io.citybuddy.commerce.checkout.CheckoutModels.Command;
import io.citybuddy.commerce.checkout.CheckoutModels.Item;
import io.citybuddy.commerce.checkout.CheckoutModels.View;
import io.citybuddy.commerce.identity.IdentityVerificationUnavailableException;
import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.order.BatchOrderService;
import io.citybuddy.commerce.order.OrderProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@ConditionalOnProperty(name = "citybuddy.orders.enabled", havingValue = "true")
public final class CheckoutController {
  private static final int MAXIMUM_BODY_BYTES = 32768;
  private static final Pattern SAFE_CORRELATION = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private final DirectUserAuthorizer authorizer;
  private final BatchOrderService service;
  private final OrderProperties properties;
  private final ObjectReader reader;

  public CheckoutController(
      DirectUserAuthorizer authorizer,
      BatchOrderService service,
      ObjectMapper mapper,
      OrderProperties properties) {
    this.authorizer = authorizer;
    this.service = service;
    this.properties = properties;
    reader =
        mapper
            .readerFor(JsonNode.class)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  @PostMapping(value = "/api/shopping/checkouts", consumes = "application/json")
  public ResponseEntity<View> create(HttpServletRequest request) {
    String owner = owner(request);
    String suppliedCorrelation = request.getHeader("X-Correlation-Id");
    String correlation =
        suppliedCorrelation != null && SAFE_CORRELATION.matcher(suppliedCorrelation).matches()
            ? suppliedCorrelation
            : UUID.randomUUID().toString();
    View result =
        service.create(owner, request.getHeader("Idempotency-Key"), body(request), correlation);
    return ResponseEntity.status(result.replayed() ? 200 : 201).body(result);
  }

  private String owner(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (authorization == null || authorization.length() > 16384) {
      throw new CheckoutException(
          401, "authentication", "Direct-user checkout authorization failed");
    }
    try {
      return authorizer
          .authorize(
              authorization,
              request.getHeader("X-Eval-Sandbox-Id"),
              properties.requiredPermission())
          .subject();
    } catch (CatalogException exception) {
      throw new CheckoutException(
          exception.status(),
          exception.status() == 403 ? "authorization" : "authentication",
          "Direct-user checkout authorization failed");
    }
  }

  private Command body(HttpServletRequest request) {
    JsonNode body;
    try {
      byte[] bytes = request.getInputStream().readNBytes(MAXIMUM_BODY_BYTES + 1);
      if (bytes.length > MAXIMUM_BODY_BYTES) {
        throw new CheckoutException(413, "validation", "Checkout request is too large");
      }
      body = reader.readValue(bytes);
    } catch (IOException exception) {
      throw invalid();
    }
    fields(body, Set.of("expectedCartVersion", "currency", "items"));
    JsonNode items = body.get("items");
    if (items == null || !items.isArray() || items.isEmpty() || items.size() > 100) {
      throw invalid();
    }
    var result = new ArrayList<Item>(items.size());
    for (JsonNode item : items) {
      fields(
          item,
          Set.of("productId", "quantity", "expectedProductVersion", "expectedUnitPriceMinor"));
      JsonNode quantity = item.get("quantity");
      if (quantity == null || !quantity.isIntegralNumber() || !quantity.canConvertToInt()) {
        throw invalid();
      }
      result.add(
          new Item(
              text(item, "productId"),
              quantity.intValue(),
              number(item, "expectedProductVersion"),
              number(item, "expectedUnitPriceMinor")));
    }
    return new Command(
        number(body, "expectedCartVersion"), text(body, "currency"), List.copyOf(result));
  }

  private static void fields(JsonNode node, Set<String> fields) {
    if (node == null || !node.isObject()) {
      throw invalid();
    }
    var names = node.fieldNames();
    while (names.hasNext()) {
      if (!fields.contains(names.next())) {
        throw invalid();
      }
    }
  }

  private static String text(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isTextual()) {
      throw invalid();
    }
    return value.textValue();
  }

  private static long number(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalid();
    }
    return value.longValue();
  }

  private static CheckoutException invalid() {
    return new CheckoutException(400, "validation", "Invalid checkout request");
  }
}

@RestControllerAdvice(assignableTypes = {CheckoutController.class, CheckoutReadController.class})
final class CheckoutExceptionHandler {
  @ExceptionHandler(CheckoutException.class)
  ResponseEntity<Map<String, String>> handle(CheckoutException exception) {
    return ResponseEntity.status(exception.status())
        .body(Map.of("category", exception.category(), "message", exception.getMessage()));
  }

  @ExceptionHandler(OboAuthorizationException.class)
  ResponseEntity<Map<String, String>> forbidden() {
    return ResponseEntity.status(403)
        .body(Map.of("category", "authorization", "message", "Forbidden"));
  }

  @ExceptionHandler(IdentityVerificationUnavailableException.class)
  ResponseEntity<Map<String, String>> unavailable() {
    return ResponseEntity.status(503)
        .body(
            Map.of(
                "category",
                "identity_unavailable",
                "message",
                "Identity verification unavailable"));
  }
}
