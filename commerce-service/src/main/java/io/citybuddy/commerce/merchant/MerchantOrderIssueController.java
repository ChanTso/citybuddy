package io.citybuddy.commerce.merchant;

import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.merchant.MerchantOrderIssueRepository.OrderIssue;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "citybuddy.merchant.enabled", havingValue = "true")
public final class MerchantOrderIssueController {
  private final OboAuthorizer obo;
  private final MerchantOrderIssueService service;

  public MerchantOrderIssueController(OboAuthorizer obo, MerchantOrderIssueService service) {
    this.obo = obo;
    this.service = service;
  }

  @GetMapping("/internal/merchant/order-issues")
  public ResponseEntity<List<OrderIssue>> list(
      @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
    String session = request.getHeader("X-Merchant-Session-Id");
    String authorization = request.getHeader("Authorization");
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
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(limit));
  }
}
