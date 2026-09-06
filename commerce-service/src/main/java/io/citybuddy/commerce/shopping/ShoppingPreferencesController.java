package io.citybuddy.commerce.shopping;

import io.citybuddy.commerce.identity.IdentityVerificationUnavailableException;
import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.identity.SupportSessionId;
import io.citybuddy.commerce.shopping.ShoppingPreferencesModels.Preferences;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@ConditionalOnProperty(
    name = {"citybuddy.catalog.enabled", "citybuddy.obo.enabled"},
    havingValue = "true")
public final class ShoppingPreferencesController {
  private final OboAuthorizer obo;
  private final ShoppingPreferencesRepository profiles;

  public ShoppingPreferencesController(OboAuthorizer obo, ShoppingPreferencesRepository profiles) {
    this.obo = obo;
    this.profiles = profiles;
  }

  @GetMapping("/internal/shopping/preferences")
  public ResponseEntity<Preferences> get(HttpServletRequest request) {
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
                "shopping:profile:read", null, session, null, null, null, "shopping-agent"));
    if (principal.sandboxId() != null || principal.subject().length() > 128) {
      throw new OboAuthorizationException("Shopping identity is invalid");
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(profiles.find(principal.subject()));
  }
}

@RestControllerAdvice(assignableTypes = ShoppingPreferencesController.class)
final class ShoppingPreferencesExceptionHandler {
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
