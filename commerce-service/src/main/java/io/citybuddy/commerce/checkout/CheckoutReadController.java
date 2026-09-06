package io.citybuddy.commerce.checkout;

import io.citybuddy.commerce.checkout.CheckoutModels.View;
import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.identity.SupportSessionId;
import io.citybuddy.commerce.order.BatchOrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
    name = {"citybuddy.orders.enabled", "citybuddy.obo.enabled"},
    havingValue = "true")
public final class CheckoutReadController {
  private final OboAuthorizer obo;
  private final BatchOrderService service;

  public CheckoutReadController(OboAuthorizer obo, BatchOrderService service) {
    this.obo = obo;
    this.service = service;
  }

  @GetMapping("/internal/shopping/checkouts/{checkoutId}")
  public ResponseEntity<View> find(@PathVariable String checkoutId, HttpServletRequest request) {
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
    View result =
        service
            .find(principal.subject(), checkoutId)
            .orElseThrow(() -> new CheckoutException(404, "not_found", "Checkout not found"));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
  }
}
