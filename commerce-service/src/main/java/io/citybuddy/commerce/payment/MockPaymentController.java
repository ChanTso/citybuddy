package io.citybuddy.commerce.payment;

import io.citybuddy.commerce.catalog.CatalogException;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import io.citybuddy.commerce.evaluation.EvaluationSandboxException;
import io.citybuddy.commerce.identity.IdentityVerificationUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@ConditionalOnProperty(name = "citybuddy.mock-payment.enabled", havingValue = "true")
public final class MockPaymentController {
  private final DirectUserAuthorizer authorizer;
  private final MockPaymentProperties properties;
  private final MockPaymentService service;

  public MockPaymentController(
      DirectUserAuthorizer authorizer,
      MockPaymentProperties properties,
      MockPaymentService service) {
    this.authorizer = authorizer;
    this.properties = properties;
    this.service = service;
  }

  @PostMapping("/api/orders/{orderId}/mock-payment")
  public ResponseEntity<MockPaymentResult> start(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String orderId,
      @RequestBody(required = false) MockPaymentRequest request) {
    DirectUserAuthorizer.DirectPrincipal subject;
    try {
      subject =
          authorizer.authorizeEvaluation(
              authorization, evalSandbox, properties.requiredPermission());
    } catch (CatalogException exception) {
      if (exception.status() == 403) {
        throw new MockPaymentException(
            403,
            "AUTHORIZATION",
            MockPaymentRejectionReason.DIRECT_USER_AUTHORIZATION_REJECTED,
            "Direct-user payment authorization failed");
      }
      throw new MockPaymentException(
          exception.status(), "AUTHENTICATION", "Direct-user payment authorization failed");
    }
    MockPaymentResult result =
        service.start(subject.subject(), subject.sandboxId(), orderId, idempotencyKey, request);
    return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
        .body(result);
  }
}

@RestController
@ConditionalOnProperty(name = "citybuddy.mock-payment.enabled", havingValue = "true")
final class MockPaymentCallbackController {
  private final MockPaymentCallbackAuthenticator authenticator;
  private final MockPaymentService service;

  MockPaymentCallbackController(
      MockPaymentCallbackAuthenticator authenticator, MockPaymentService service) {
    this.authenticator = authenticator;
    this.service = service;
  }

  @PostMapping("/internal/mock-payments/callback")
  MockPaymentCallbackResult callback(
      @RequestHeader(value = "X-Mock-Payment-Key-Id", required = false) String keyId,
      @RequestHeader(value = "X-Mock-Payment-Timestamp", required = false) String timestamp,
      @RequestHeader(value = "X-Mock-Payment-Signature", required = false) String signature,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) MockPaymentCallbackRequest request) {
    authenticator.authenticate(keyId, timestamp, signature, idempotencyKey, request);
    return service.callback(idempotencyKey, request);
  }
}

@RestControllerAdvice(
    assignableTypes = {MockPaymentController.class, MockPaymentCallbackController.class})
final class MockPaymentExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(MockPaymentExceptionHandler.class);

  @ExceptionHandler(MockPaymentException.class)
  ResponseEntity<Map<String, String>> handle(MockPaymentException exception) {
    if (exception.status() == 403) {
      LOG.warn("evaluation_request_rejected reason_code={}", exception.reason());
    }
    return ResponseEntity.status(exception.status())
        .body(Map.of("category", exception.category(), "message", exception.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, String>> handleUnreadable(HttpServletRequest request) {
    String path = request.getRequestURI();
    String message =
        path.startsWith("/internal/")
            ? "Payment callback is invalid"
            : "Payment request is invalid";
    return ResponseEntity.badRequest().body(Map.of("category", "VALIDATION", "message", message));
  }

  @ExceptionHandler(EvaluationSandboxException.class)
  ResponseEntity<Map<String, String>> handleSandbox(EvaluationSandboxException exception) {
    LOG.warn("evaluation_request_rejected reason_code={}", exception.reason());
    return ResponseEntity.status(403)
        .body(Map.of("category", "AUTHORIZATION", "message", "Evaluation payment is unavailable"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<Map<String, String>> handleUnavailable() {
    return ResponseEntity.status(503)
        .body(Map.of("category", "UNAVAILABLE", "message", "Payment service is unavailable"));
  }

  @ExceptionHandler(IdentityVerificationUnavailableException.class)
  ResponseEntity<Map<String, String>> handleIdentityUnavailable() {
    return ResponseEntity.status(503)
        .body(Map.of("category", "UNAVAILABLE", "message", "Payment service is unavailable"));
  }
}
