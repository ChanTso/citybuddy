package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.citybuddy.commerce.catalog.CatalogException;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import io.citybuddy.commerce.identity.IdentityVerificationUnavailableException;
import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.merchant.MerchantChangeModels.Command;
import io.citybuddy.commerce.merchant.MerchantChangeModels.View;
import io.citybuddy.commerce.merchant.MerchantModels.Context;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@ConditionalOnProperty(name = "citybuddy.merchant.enabled", havingValue = "true")
public final class MerchantChangeController {
  private static final int MAXIMUM_BODY_BYTES = 65_536;
  private final OboAuthorizer obo;
  private final DirectUserAuthorizer direct;
  private final MerchantChangeService service;
  private final ObjectReader reader;

  public MerchantChangeController(
      OboAuthorizer obo,
      DirectUserAuthorizer direct,
      MerchantChangeService service,
      ObjectMapper mapper) {
    this.obo = obo;
    this.direct = direct;
    this.service = service;
    reader =
        mapper
            .readerFor(JsonNode.class)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  @PostMapping("/internal/merchant/changes")
  public ResponseEntity<View> prepare(HttpServletRequest request) {
    Context context = context(request, "merchant:change:prepare");
    requireParameters(request, Set.of());
    String key = oneHeader(request, "Idempotency-Key");
    JsonNode body = body(request);
    if (body == null
        || !body.isObject()
        || body.size() != 2
        || !body.path("kind").isTextual()
        || !body.path("payload").isObject()) {
      throw invalid("A change requires kind and payload");
    }
    return result(
        service.prepare(
            context, key, new Command(body.path("kind").textValue(), body.path("payload"))));
  }

  @GetMapping("/internal/merchant/changes")
  public ResponseEntity<List<View>> list(HttpServletRequest request) {
    Context context = context(request, "merchant:change:read");
    requireParameters(request, Set.of("state", "limit", "offset"));
    String state = parameter(request, "state");
    int limit = integerParameter(request, "limit", 20, 1, 100);
    int offset = integerParameter(request, "offset", 0, 0, 10_000);
    return result(service.list(context, state, limit, offset));
  }

  @GetMapping("/internal/merchant/changes/{changeId}")
  public ResponseEntity<View> get(@PathVariable String changeId, HttpServletRequest request) {
    Context context = context(request, "merchant:change:read");
    requireParameters(request, Set.of());
    return result(service.get(context, changeId));
  }

  @PostMapping("/internal/merchant/changes/{changeId}/cancel")
  public ResponseEntity<View> cancel(@PathVariable String changeId, HttpServletRequest request) {
    Context context = context(request, "merchant:change:cancel");
    requireParameters(request, Set.of());
    emptyBody(request);
    return result(service.cancel(context, changeId));
  }

  @PostMapping("/api/merchant/changes/{changeId}/apply")
  public ResponseEntity<View> apply(@PathVariable String changeId, HttpServletRequest request) {
    var operator =
        direct.authorize(
            oneHeader(request, "Authorization"),
            oneHeader(request, "X-Eval-Sandbox-Id"),
            "merchant:change:apply");
    requireParameters(request, Set.of());
    emptyBody(request);
    View view = service.apply(operator.subject(), changeId);
    return ResponseEntity.status("APPLIED".equals(view.state()) ? 200 : 409)
        .cacheControl(CacheControl.noStore())
        .body(view);
  }

  private Context context(HttpServletRequest request, String scope) {
    if (request.getHeader("X-Eval-Sandbox-Id") != null) {
      throw new OboAuthorizationException("Evaluation context is not supported");
    }
    String session = oneHeader(request, "X-Merchant-Session-Id");
    String authorization = oneHeader(request, "Authorization");
    if (session == null || session.isBlank() || session.length() > 128) {
      throw new OboAuthorizationException("Merchant session is required");
    }
    if (authorization == null
        || !authorization.startsWith("Bearer ")
        || authorization.length() > 16_384) {
      throw new OboAuthorizationException("OBO bearer is required");
    }
    var principal =
        obo.authorize(
            authorization.substring(7),
            new OboAuthorizer.AuthorizationRequest(
                scope, null, session, null, null, null, "merchant-agent"));
    if (principal.sandboxId() != null || principal.subject().length() > 128) {
      throw new OboAuthorizationException("Merchant identity is invalid");
    }
    return new Context(principal.subject(), session);
  }

  private static String oneHeader(HttpServletRequest request, String name) {
    var values = request.getHeaders(name);
    if (!values.hasMoreElements()) {
      return null;
    }
    String value = values.nextElement();
    if (values.hasMoreElements()) {
      throw invalid("Header must occur once");
    }
    return value;
  }

  private static void requireParameters(HttpServletRequest request, Set<String> allowed) {
    if (!allowed.containsAll(request.getParameterMap().keySet())) {
      throw invalid("Unknown query parameter");
    }
  }

  private static String parameter(HttpServletRequest request, String name) {
    String[] values = request.getParameterValues(name);
    if (values == null) {
      return null;
    }
    if (values.length != 1) {
      throw invalid("Query parameter must occur once");
    }
    return values[0];
  }

  private static int integerParameter(
      HttpServletRequest request, String name, int fallback, int minimum, int maximum) {
    String value = parameter(request, name);
    if (value == null) {
      return fallback;
    }
    if (!value.matches("[0-9]{1,5}")) {
      throw invalid("Pagination requires an integer");
    }
    int parsed = Integer.parseInt(value);
    if (parsed < minimum || parsed > maximum) {
      throw invalid("Pagination is out of range");
    }
    return parsed;
  }

  private void emptyBody(HttpServletRequest request) {
    JsonNode body = body(request);
    if (body != null && (!body.isObject() || !body.isEmpty())) {
      throw invalid("This action requires an empty body");
    }
  }

  private JsonNode body(HttpServletRequest request) {
    try {
      byte[] bytes = request.getInputStream().readNBytes(MAXIMUM_BODY_BYTES + 1);
      if (bytes.length > MAXIMUM_BODY_BYTES) {
        throw new MerchantException(413, "VALIDATION", "Merchant request is too large");
      }
      return bytes.length == 0 ? null : reader.readValue(bytes);
    } catch (IOException exception) {
      throw invalid("Request must contain valid JSON");
    }
  }

  private static <T> ResponseEntity<T> result(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  private static MerchantException invalid(String message) {
    return new MerchantException(400, "VALIDATION", message);
  }
}

@RestControllerAdvice(assignableTypes = MerchantChangeController.class)
final class MerchantChangeExceptionHandler {
  @ExceptionHandler(MerchantException.class)
  ResponseEntity<Map<String, String>> merchant(MerchantException exception) {
    return ResponseEntity.status(exception.status())
        .cacheControl(CacheControl.noStore())
        .body(Map.of("category", exception.category(), "message", exception.getMessage()));
  }

  @ExceptionHandler(OboAuthorizationException.class)
  ResponseEntity<Map<String, String>> forbidden() {
    return ResponseEntity.status(403)
        .cacheControl(CacheControl.noStore())
        .body(Map.of("category", "AUTHORIZATION", "message", "Forbidden"));
  }

  @ExceptionHandler(CatalogException.class)
  ResponseEntity<Map<String, String>> direct(CatalogException exception) {
    return ResponseEntity.status(exception.status())
        .cacheControl(CacheControl.noStore())
        .body(
            Map.of("category", "AUTHORIZATION", "message", "Direct operator authorization failed"));
  }

  @ExceptionHandler(IdentityVerificationUnavailableException.class)
  ResponseEntity<Map<String, String>> unavailable() {
    return ResponseEntity.status(503)
        .cacheControl(CacheControl.noStore())
        .body(
            Map.of(
                "category",
                "IDENTITY_UNAVAILABLE",
                "message",
                "Identity verification unavailable"));
  }
}
