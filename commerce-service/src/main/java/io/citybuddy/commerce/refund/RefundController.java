package io.citybuddy.commerce.refund;

import io.citybuddy.commerce.catalog.CatalogException;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@ConditionalOnProperty(name = "citybuddy.refund.enabled", havingValue = "true")
public final class RefundController {
  private final DirectUserAuthorizer authorizer;
  private final RefundProperties properties;
  private final RefundService service;
  private final RefundRequestParser requests;

  public RefundController(
      DirectUserAuthorizer authorizer,
      RefundProperties properties,
      RefundService service,
      RefundRequestParser requests) {
    this.authorizer = authorizer;
    this.properties = properties;
    this.service = service;
    this.requests = requests;
  }

  @PostMapping("/api/orders/{orderId}/refunds")
  public ResponseEntity<RefundResult> request(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String orderId,
      HttpServletRequest request) {
    String subject = authorize(authorization, evalSandbox);
    RefundResult result =
        service.request(subject, orderId, idempotencyKey, requests.parse(request));
    return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
        .body(result);
  }

  @GetMapping("/api/refunds/{refundId}")
  public RefundResult status(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox,
      @PathVariable String refundId) {
    return service.status(authorize(authorization, evalSandbox), refundId);
  }

  private String authorize(String authorization, String evalSandbox) {
    try {
      return authorizer
          .authorize(authorization, evalSandbox, properties.requiredPermission())
          .subject();
    } catch (CatalogException exception) {
      throw new RefundException(
          exception.status(),
          exception.status() == 403 ? "AUTHORIZATION" : "AUTHENTICATION",
          "Direct-user refund authorization failed");
    }
  }
}

@RestControllerAdvice(assignableTypes = RefundController.class)
final class RefundExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(RefundExceptionHandler.class);

  @ExceptionHandler(RefundException.class)
  ResponseEntity<Map<String, String>> handle(RefundException exception) {
    if (exception.reason() != RefundRejectionReason.NOT_APPLICABLE) {
      LOG.warn("refund_request_rejected reason_code={}", exception.reason());
    }
    return ResponseEntity.status(exception.status())
        .body(Map.of("category", exception.category(), "message", exception.getMessage()));
  }

  @ExceptionHandler({
    DataAccessResourceFailureException.class,
    CannotCreateTransactionException.class
  })
  ResponseEntity<Map<String, String>> handleUnavailable(RuntimeException exception) {
    LOG.warn(
        "refund_request_rejected reason_code={}",
        RefundRejectionReason.REFUND_DEPENDENCY_UNAVAILABLE);
    return ResponseEntity.status(503)
        .body(Map.of("category", "UNAVAILABLE", "message", "Refund service is unavailable"));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, String>> handleUnreadable(HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(Map.of("category", "VALIDATION", "message", "Refund request is invalid"));
  }
}
