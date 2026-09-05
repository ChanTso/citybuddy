package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.catalog.CatalogException;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import io.citybuddy.commerce.identity.IdentityVerificationUnavailableException;
import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.merchant.MerchantModels.Context;
import io.citybuddy.commerce.merchant.MerchantModels.DraftView;
import io.citybuddy.commerce.merchant.MerchantModels.PrepareCommand;
import io.citybuddy.commerce.merchant.MerchantModels.PriceInput;
import io.citybuddy.commerce.merchant.MerchantModels.ProductView;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@ConditionalOnProperty(name = "citybuddy.merchant.enabled", havingValue = "true")
public final class MerchantController {
  private static final int MAXIMUM_BODY_BYTES = 8192;
  private final OboAuthorizer obo;
  private final DirectUserAuthorizer direct;
  private final MerchantService service;
  private final ObjectMapper mapper;

  public MerchantController(
      OboAuthorizer obo,
      DirectUserAuthorizer direct,
      MerchantService service,
      ObjectMapper mapper) {
    this.obo = obo;
    this.direct = direct;
    this.service = service;
    this.mapper = mapper;
  }

  @GetMapping("/internal/merchant/products")
  public List<ProductView> products(HttpServletRequest request) {
    context(request, "merchant:read");
    return service.products();
  }

  @GetMapping("/internal/merchant/products/{productId}")
  public ProductView product(@PathVariable String productId, HttpServletRequest request) {
    context(request, "merchant:read");
    return service.product(productId);
  }

  @GetMapping("/internal/merchant/summary")
  public Map<String, Object> summary(
      @RequestParam String start, @RequestParam String end, HttpServletRequest request) {
    context(request, "merchant:read");
    try {
      return service.summary(Instant.parse(start), Instant.parse(end));
    } catch (DateTimeParseException exception) {
      throw MerchantService.invalid("Period timestamps must be ISO-8601 instants");
    }
  }

  @PostMapping("/internal/merchant/price-drafts")
  public DraftView prepare(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest request) {
    Context context = context(request, "merchant:price:prepare");
    JsonNode body = body(request);
    if (body == null
        || !fields(body).equals(Set.of("currency", "items"))
        || !body.path("currency").isTextual()
        || !body.path("items").isArray()) {
      throw MerchantService.invalid("A proposal requires currency and items");
    }
    List<PriceInput> items = new ArrayList<>();
    for (JsonNode item : body.path("items")) {
      if (!fields(item).equals(Set.of("productId", "newPriceMinor"))
          || !item.path("productId").isTextual()
          || !item.path("newPriceMinor").isIntegralNumber()
          || !item.path("newPriceMinor").canConvertToLong()) {
        throw MerchantService.invalid("A price item requires productId and integer newPriceMinor");
      }
      items.add(
          new PriceInput(
              item.path("productId").textValue(), item.path("newPriceMinor").longValue()));
    }
    return service.prepare(
        context, key, new PrepareCommand(body.path("currency").textValue(), items));
  }

  @GetMapping("/internal/merchant/price-drafts/{draftId}")
  public DraftView get(@PathVariable String draftId, HttpServletRequest request) {
    return service.get(context(request, "merchant:price:read"), draftId);
  }

  @PostMapping("/internal/merchant/price-drafts/{draftId}/cancel")
  public DraftView cancel(@PathVariable String draftId, HttpServletRequest request) {
    Context context = context(request, "merchant:price:cancel");
    emptyBody(request);
    return service.cancel(context, draftId);
  }

  @PostMapping("/api/merchant/price-drafts/{draftId}/apply")
  public ResponseEntity<DraftView> apply(@PathVariable String draftId, HttpServletRequest request) {
    var operator =
        direct.authorize(
            request.getHeader("Authorization"),
            request.getHeader("X-Eval-Sandbox-Id"),
            "merchant:price:apply");
    emptyBody(request);
    DraftView result = service.apply(operator.subject(), draftId);
    return ResponseEntity.status(result.state().equals("APPLIED") ? 200 : 409).body(result);
  }

  private Context context(HttpServletRequest request, String scope) {
    String session = request.getHeader("X-Merchant-Session-Id");
    if (session == null || session.isBlank() || session.length() > 128) {
      throw new OboAuthorizationException("Merchant session is required");
    }
    String authorization = request.getHeader("Authorization");
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      throw new OboAuthorizationException("OBO bearer is required");
    }
    var principal =
        obo.authorize(
            authorization.substring(7),
            new OboAuthorizer.AuthorizationRequest(
                scope,
                null,
                session,
                null,
                null,
                request.getHeader("X-Eval-Sandbox-Id"),
                "merchant-agent"));
    if (principal.sandboxId() != null || principal.subject().length() > 128) {
      throw new OboAuthorizationException("Merchant identity is invalid");
    }
    return new Context(principal.subject(), session);
  }

  private void emptyBody(HttpServletRequest request) {
    JsonNode body = body(request);
    if (body != null && (!body.isObject() || !body.isEmpty())) {
      throw MerchantService.invalid("This action requires an empty body");
    }
  }

  private JsonNode body(HttpServletRequest request) {
    try {
      byte[] bytes = request.getInputStream().readNBytes(MAXIMUM_BODY_BYTES + 1);
      if (bytes.length > MAXIMUM_BODY_BYTES) {
        throw MerchantService.invalid("Merchant request is too large");
      }
      if (bytes.length == 0) {
        return null;
      }
      return mapper
          .reader()
          .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .readTree(bytes);
    } catch (IOException exception) {
      throw MerchantService.invalid("Request must contain valid JSON");
    }
  }

  private static Set<String> fields(JsonNode value) {
    if (!value.isObject()) {
      return Set.of();
    }
    Set<String> names = new HashSet<>();
    value.fieldNames().forEachRemaining(names::add);
    return names;
  }
}

@RestControllerAdvice(assignableTypes = MerchantController.class)
final class MerchantExceptionHandler {
  @ExceptionHandler(MerchantException.class)
  ResponseEntity<Map<String, String>> merchant(MerchantException exception) {
    return ResponseEntity.status(exception.status())
        .body(Map.of("category", exception.category(), "message", exception.getMessage()));
  }

  @ExceptionHandler(OboAuthorizationException.class)
  ResponseEntity<Map<String, String>> obo() {
    return ResponseEntity.status(403)
        .body(Map.of("category", "AUTHORIZATION", "message", "Forbidden"));
  }

  @ExceptionHandler(CatalogException.class)
  ResponseEntity<Map<String, String>> direct(CatalogException exception) {
    return ResponseEntity.status(exception.status())
        .body(
            Map.of("category", "AUTHORIZATION", "message", "Direct operator authorization failed"));
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
