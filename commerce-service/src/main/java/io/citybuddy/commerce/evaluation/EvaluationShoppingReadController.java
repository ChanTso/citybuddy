package io.citybuddy.commerce.evaluation;

import io.citybuddy.commerce.cart.CartException;
import io.citybuddy.commerce.cart.CartModels.CartView;
import io.citybuddy.commerce.catalog.CatalogException;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import io.citybuddy.commerce.identity.IdentityVerificationUnavailableException;
import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.identity.SupportSessionId;
import io.citybuddy.commerce.retail.RetailPolicyModels.Policy;
import io.citybuddy.commerce.shopping.ShoppingOrderModels.OrderView;
import io.citybuddy.commerce.shopping.ShoppingPreferencesModels.Preferences;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@Profile("evaluation")
@ConditionalOnProperty(name = "citybuddy.obo.enabled", havingValue = "true")
public final class EvaluationShoppingReadController {
  private final OboAuthorizer obo;
  private final DirectUserAuthorizer direct;
  private final EvaluationSandboxAccess access;
  private final EvaluationShoppingReadService service;

  public EvaluationShoppingReadController(
      OboAuthorizer obo,
      DirectUserAuthorizer direct,
      EvaluationSandboxAccess access,
      EvaluationShoppingReadService service) {
    this.obo = obo;
    this.direct = direct;
    this.access = access;
    this.service = service;
  }

  @GetMapping("/internal/eval/shopping/orders")
  public ResponseEntity<List<OrderView>> orders(HttpServletRequest request) {
    var principal = authorize(request, "shopping:orders:read");
    parameters(request, Set.of("limit"));
    int limit = 20;
    String value = request.getParameter("limit");
    if (value != null) {
      try {
        limit = Integer.parseInt(value);
      } catch (NumberFormatException exception) {
        throw invalid();
      }
    }
    if (limit < 1 || limit > 50) {
      throw invalid();
    }
    return ok(service.list(principal.subject(), principal.sandboxId(), limit));
  }

  @GetMapping("/internal/eval/shopping/orders/{orderId}")
  public ResponseEntity<OrderView> order(@PathVariable String orderId, HttpServletRequest request) {
    var principal = authorize(request, "shopping:orders:read");
    parameters(request, Set.of());
    if (orderId.length() > 128) {
      return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build();
    }
    return service
        .find(principal.subject(), principal.sandboxId(), orderId)
        .map(EvaluationShoppingReadController::ok)
        .orElseGet(() -> ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build());
  }

  @GetMapping("/internal/eval/shopping/preferences")
  public ResponseEntity<Preferences> preferences(HttpServletRequest request) {
    var principal = authorize(request, "shopping:profile:read");
    parameters(request, Set.of());
    return ok(service.preferences(principal.subject()));
  }

  @GetMapping("/internal/eval/shopping/cart")
  public ResponseEntity<CartView> cart(HttpServletRequest request) {
    var principal = authorize(request, "shopping:cart:read");
    parameters(request, Set.of());
    return ok(service.cart(principal.subject()));
  }

  @GetMapping("/internal/eval/shopping/policies")
  public ResponseEntity<List<Policy>> policies(HttpServletRequest request) {
    String sandbox = sandbox(request);
    var principal = direct.authorizeEvaluation(bearer(request), sandbox, "shopping:session:create");
    // The legacy direct verifier permits handleless evaluation tokens; this new surface does not.
    if (principal.evaluationHandle() == null || principal.subject().length() > 128) {
      throw new OboAuthorizationException("Evaluation identity is required");
    }
    access.requireActive(sandbox);
    parameters(request, Set.of("query"));
    try {
      return ok(service.policies(request.getParameter("query")));
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
  }

  private OboAuthorizer.OboPrincipal authorize(HttpServletRequest request, String scope) {
    String sandbox = sandbox(request);
    String session = singleHeader(request, "X-Shopping-Session-Id");
    if (!SupportSessionId.isValid(session)) {
      throw new OboAuthorizationException("Shopping session is required");
    }
    var principal =
        obo.authorize(
            bearer(request).substring(7),
            new OboAuthorizer.AuthorizationRequest(
                scope, null, session, null, null, sandbox, "shopping-agent"));
    if (principal.subject().length() > 128) {
      throw new OboAuthorizationException("Shopping identity is invalid");
    }
    access.requireActive(sandbox);
    return principal;
  }

  private static String sandbox(HttpServletRequest request) {
    return EvaluationRequestParser.boundedHeader(
        singleHeader(request, "X-Eval-Sandbox-Id"), 64, "Invalid sandbox");
  }

  private static String bearer(HttpServletRequest request) {
    String value = singleHeader(request, "Authorization");
    if (!value.startsWith("Bearer ") || value.length() > 16384) {
      throw new OboAuthorizationException("Bearer is required");
    }
    return value;
  }

  private static String singleHeader(HttpServletRequest request, String name) {
    var values = Collections.list(request.getHeaders(name));
    if (values.size() != 1) {
      throw new OboAuthorizationException("A single identity header is required");
    }
    return values.getFirst();
  }

  private static void parameters(HttpServletRequest request, Set<String> allowed) {
    for (var entry : request.getParameterMap().entrySet()) {
      if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1) {
        throw invalid();
      }
    }
  }

  private static EvaluationSandboxException invalid() {
    return new EvaluationSandboxException(400, "Invalid shopping read request");
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}

@RestControllerAdvice(assignableTypes = EvaluationShoppingReadController.class)
final class EvaluationShoppingReadExceptionHandler {
  @ExceptionHandler(OboAuthorizationException.class)
  ResponseEntity<Map<String, String>> forbidden() {
    return error(403, "Forbidden");
  }

  @ExceptionHandler(CatalogException.class)
  ResponseEntity<Map<String, String>> unauthorized(CatalogException exception) {
    return error(exception.status(), "Forbidden");
  }

  @ExceptionHandler(EvaluationSandboxException.class)
  ResponseEntity<Map<String, String>> rejected(EvaluationSandboxException exception) {
    return error(exception.status(), exception.status() == 403 ? "Forbidden" : "Bad request");
  }

  @ExceptionHandler({IdentityVerificationUnavailableException.class, DataAccessException.class})
  ResponseEntity<Map<String, String>> unavailable() {
    return error(503, "Service unavailable");
  }

  @ExceptionHandler(CartException.class)
  ResponseEntity<Map<String, String>> cartUnavailable(CartException exception) {
    return error(exception.status(), "Cart unavailable");
  }

  private static ResponseEntity<Map<String, String>> error(int status, String message) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .body(Map.of("error", message));
  }
}
