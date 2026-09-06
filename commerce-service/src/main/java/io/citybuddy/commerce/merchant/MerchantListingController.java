package io.citybuddy.commerce.merchant;

import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.merchant.MerchantListingModels.InventoryAlert;
import io.citybuddy.commerce.merchant.MerchantListingModels.Listing;
import io.citybuddy.commerce.merchant.MerchantListingModels.Page;
import io.citybuddy.commerce.merchant.MerchantListingModels.Search;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "citybuddy.merchant.enabled", havingValue = "true")
public final class MerchantListingController {
  private final OboAuthorizer obo;
  private final MerchantListingService service;

  public MerchantListingController(OboAuthorizer obo, MerchantListingService service) {
    this.obo = obo;
    this.service = service;
  }

  @GetMapping("/internal/merchant/listings")
  public ResponseEntity<Page<Listing>> search(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) Long maxStock,
      @RequestParam(required = false) String contentQuality,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(required = false) Instant asOf,
      HttpServletRequest request) {
    authorize(
        request,
        Set.of(
            "query",
            "status",
            "category",
            "maxStock",
            "contentQuality",
            "currency",
            "sort",
            "limit",
            "offset",
            "asOf"));
    return response(
        service.search(
            new Search(
                query, status, category, maxStock, contentQuality, currency, sort, limit, offset),
            asOf));
  }

  @GetMapping("/internal/merchant/listings/{id}")
  public ResponseEntity<Listing> get(
      @PathVariable String id,
      @RequestParam(required = false) Instant asOf,
      HttpServletRequest request) {
    authorize(request, Set.of("asOf"));
    return response(service.get(id, asOf));
  }

  @GetMapping("/internal/merchant/inventory-alerts")
  public ResponseEntity<Page<InventoryAlert>> alerts(
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(required = false) Instant asOf,
      HttpServletRequest request) {
    authorize(request, Set.of("limit", "offset", "asOf"));
    return response(service.alerts(limit, offset, asOf));
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
    if (request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
      throw new MerchantException(400, "VALIDATION", "Query parameters must occur once");
    }
    if (!parameters.containsAll(request.getParameterMap().keySet())) {
      throw new MerchantException(400, "VALIDATION", "Unknown query parameter");
    }
  }

  private static String oneHeader(HttpServletRequest request, String name) {
    var values = Collections.list(request.getHeaders(name));
    if (values.size() > 1) {
      throw new OboAuthorizationException("Merchant identity headers must occur once");
    }
    return values.isEmpty() ? null : values.getFirst();
  }

  private static <T> ResponseEntity<T> response(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
