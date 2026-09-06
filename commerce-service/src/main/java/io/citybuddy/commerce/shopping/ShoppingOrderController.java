package io.citybuddy.commerce.shopping;

import io.citybuddy.commerce.identity.IdentityVerificationUnavailableException;
import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.identity.SupportSessionId;
import io.citybuddy.commerce.shopping.ShoppingOrderModels.OrderView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@ConditionalOnProperty(
    name = {"citybuddy.orders.enabled", "citybuddy.obo.enabled"},
    havingValue = "true")
public final class ShoppingOrderController {
  private final OboAuthorizer obo;
  private final ShoppingOrderService service;

  public ShoppingOrderController(OboAuthorizer obo, ShoppingOrderService service) {
    this.obo = obo;
    this.service = service;
  }

  @GetMapping("/internal/shopping/orders")
  public ResponseEntity<List<OrderView>> list(
      @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
    String owner = owner(request);
    if (limit < 1 || limit > 50) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(service.list(owner, limit));
  }

  @GetMapping("/internal/shopping/orders/{orderId}")
  public ResponseEntity<OrderView> find(@PathVariable String orderId, HttpServletRequest request) {
    String owner = owner(request);
    if (orderId.length() > 128) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.of(service.find(owner, orderId));
  }

  private String owner(HttpServletRequest request) {
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
                "shopping:orders:read", null, session, null, null, null, "shopping-agent"));
    if (principal.sandboxId() != null || principal.subject().length() > 128) {
      throw new OboAuthorizationException("Shopping identity is invalid");
    }
    return principal.subject();
  }
}

@RestControllerAdvice(assignableTypes = ShoppingOrderController.class)
final class ShoppingOrderExceptionHandler {
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
