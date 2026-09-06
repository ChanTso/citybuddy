package io.citybuddy.commerce.merchant;

import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.merchant.MerchantMarketingModels.Campaign;
import io.citybuddy.commerce.merchant.MerchantMarketingModels.Promotion;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "citybuddy.merchant.enabled", havingValue = "true")
public final class MerchantMarketingController {
  private final OboAuthorizer obo;
  private final MerchantMarketingService service;

  public MerchantMarketingController(OboAuthorizer obo, MerchantMarketingService service) {
    this.obo = obo;
    this.service = service;
  }

  @GetMapping("/internal/merchant/campaigns")
  public ResponseEntity<List<Campaign>> campaigns(HttpServletRequest request) {
    authorize(request, Set.of("limit", "offset"));
    return response(
        service.campaigns(integer(request, "limit", 20), integer(request, "offset", 0)));
  }

  @GetMapping("/internal/merchant/campaigns/{id}")
  public ResponseEntity<Campaign> campaign(@PathVariable String id, HttpServletRequest request) {
    authorize(request, Set.of());
    return response(service.campaign(id));
  }

  @GetMapping("/internal/merchant/promotions")
  public ResponseEntity<List<Promotion>> promotions(HttpServletRequest request) {
    authorize(request, Set.of("limit", "offset"));
    return response(
        service.promotions(integer(request, "limit", 20), integer(request, "offset", 0)));
  }

  @GetMapping("/internal/merchant/promotions/{id}")
  public ResponseEntity<Promotion> promotion(@PathVariable String id, HttpServletRequest request) {
    authorize(request, Set.of());
    return response(service.promotion(id));
  }

  private void authorize(HttpServletRequest request, Set<String> parameters) {
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
    if (!parameters.containsAll(request.getParameterMap().keySet())
        || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
      throw MerchantService.invalid("Unexpected or repeated marketing query parameter");
    }
  }

  private static String oneHeader(HttpServletRequest request, String name) {
    var values = Collections.list(request.getHeaders(name));
    if (values.size() > 1) {
      throw new OboAuthorizationException("Merchant identity headers must occur once");
    }
    return values.isEmpty() ? null : values.getFirst();
  }

  private static int integer(HttpServletRequest request, String name, int fallback) {
    String value = request.getParameter(name);
    if (value == null) {
      return fallback;
    }
    if (!value.matches("[0-9]{1,5}")) {
      throw MerchantService.invalid("Pagination requires an integer");
    }
    return Integer.parseInt(value);
  }

  private static <T> ResponseEntity<T> response(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
