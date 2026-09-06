package io.citybuddy.commerce.merchant;

import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.shopping.ShoppingOrderModels.OrderView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "citybuddy.merchant.enabled", havingValue = "true")
public final class MerchantOrderController {
  private final OboAuthorizer obo;
  private final MerchantOrderService service;

  public MerchantOrderController(OboAuthorizer obo, MerchantOrderService service) {
    this.obo = obo;
    this.service = service;
  }

  @GetMapping("/internal/merchant/orders")
  public ResponseEntity<List<OrderView>> list(HttpServletRequest request) {
    authorize(request);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.list(limit(request)));
  }

  private void authorize(HttpServletRequest request) {
    String session = oneHeader(request, "X-Merchant-Session-Id");
    String authorization = oneHeader(request, "Authorization");
    if (session == null
        || session.isBlank()
        || session.length() > 128
        || authorization == null
        || !authorization.startsWith("Bearer ")
        || authorization.length() > 16384
        || request.getHeader("X-Eval-Sandbox-Id") != null) {
      throw new OboAuthorizationException("Merchant context is required");
    }
    var principal =
        obo.authorize(
            authorization.substring(7),
            new OboAuthorizer.AuthorizationRequest(
                "merchant:read", null, session, null, null, null, "merchant-agent"));
    if (principal.sandboxId() != null || principal.subject().length() > 128) {
      throw new OboAuthorizationException("Merchant identity is invalid");
    }
  }

  private static int limit(HttpServletRequest request) {
    if (!Set.of("limit").containsAll(request.getParameterMap().keySet())) {
      throw new MerchantException(400, "VALIDATION", "Unknown query parameter");
    }
    if (request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
      throw new MerchantException(400, "VALIDATION", "Query parameters must occur once");
    }
    String value = request.getParameter("limit");
    if (value == null) {
      return 6;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      throw new MerchantException(400, "VALIDATION", "Order limit must be an integer");
    }
  }

  private static String oneHeader(HttpServletRequest request, String name) {
    var values = Collections.list(request.getHeaders(name));
    if (values.size() > 1) {
      throw new OboAuthorizationException("Merchant identity headers must occur once");
    }
    return values.isEmpty() ? null : values.getFirst();
  }
}
