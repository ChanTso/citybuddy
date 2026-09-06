package io.citybuddy.commerce.retail;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.citybuddy.commerce.catalog.CatalogException;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.DeliveryEstimate;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.EstimateItem;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.EstimateRequest;
import io.citybuddy.commerce.retail.RetailPolicyModels.Policy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@ConditionalOnProperty(name = "citybuddy.catalog.enabled", havingValue = "true")
public final class RetailFactsController {
  private static final int MAXIMUM_BODY_BYTES = 16384;
  private final DirectUserAuthorizer authorizer;
  private final RetailPolicyRepository policies;
  private final RetailFulfillmentService fulfillment;
  private final ObjectReader reader;

  public RetailFactsController(
      DirectUserAuthorizer authorizer,
      RetailPolicyRepository policies,
      RetailFulfillmentService fulfillment,
      ObjectMapper mapper) {
    this.authorizer = authorizer;
    this.policies = policies;
    this.fulfillment = fulfillment;
    reader =
        mapper
            .readerFor(JsonNode.class)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  @GetMapping("/api/retail/policies")
  public ResponseEntity<List<Policy>> policies(HttpServletRequest request) {
    authorize(request);
    String[] values = request.getParameterValues("query");
    if (values == null
        || values.length != 1
        || !request.getParameterMap().keySet().equals(Set.of("query"))) {
      throw invalid();
    }
    try {
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(policies.search(values[0]));
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
  }

  @PostMapping(value = "/api/retail/fulfillment-options", consumes = "application/json")
  public ResponseEntity<DeliveryEstimate> estimate(HttpServletRequest request) {
    String owner = authorize(request);
    EstimateRequest estimate = estimateRequest(request);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(fulfillment.estimate(owner, estimate));
  }

  private String authorize(HttpServletRequest request) {
    return authorizer
        .authorize(
            request.getHeader("Authorization"),
            request.getHeader("X-Eval-Sandbox-Id"),
            "catalog:read")
        .subject();
  }

  private EstimateRequest estimateRequest(HttpServletRequest request) {
    try {
      byte[] bytes = request.getInputStream().readNBytes(MAXIMUM_BODY_BYTES + 1);
      if (bytes.length > MAXIMUM_BODY_BYTES) {
        throw new CatalogException(413, "Delivery estimate request is too large");
      }
      JsonNode body = reader.readValue(bytes);
      if (body == null
          || !body.isObject()
          || body.size() != 1
          || !body.has("items")
          || !body.get("items").isArray()
          || body.get("items").size() > 100) {
        throw invalid();
      }
      List<EstimateItem> items = new ArrayList<>();
      for (JsonNode item : body.get("items")) {
        if (!item.isObject()
            || item.size() != 2
            || !item.has("productId")
            || !item.has("quantity")
            || !item.get("productId").isTextual()
            || !item.get("quantity").isIntegralNumber()
            || !item.get("quantity").canConvertToInt()) {
          throw invalid();
        }
        items.add(
            new EstimateItem(item.get("productId").textValue(), item.get("quantity").intValue()));
      }
      return new EstimateRequest(items);
    } catch (IOException | IllegalArgumentException exception) {
      throw invalid();
    }
  }

  private static CatalogException invalid() {
    return new CatalogException(400, "Invalid retail facts request");
  }
}

@RestControllerAdvice(assignableTypes = RetailFactsController.class)
final class RetailFactsExceptionHandler {
  @ExceptionHandler(RetailFulfillmentException.class)
  ResponseEntity<Map<String, String>> unquotable(RetailFulfillmentException exception) {
    return ResponseEntity.unprocessableEntity()
        .body(Map.of("category", exception.category(), "message", exception.getMessage()));
  }
}
