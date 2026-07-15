package io.citybuddy.commerce.order;

import io.citybuddy.commerce.catalog.CatalogException;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@ConditionalOnProperty(name = "citybuddy.orders.enabled", havingValue = "true")
public final class OrderController {
  private static final Pattern SAFE_CORRELATION = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private final DirectUserAuthorizer authorizer;
  private final OrderService service;
  private final OrderProperties properties;

  public OrderController(
      DirectUserAuthorizer authorizer, OrderService service, OrderProperties properties) {
    this.authorizer = authorizer;
    this.service = service;
    this.properties = properties;
  }

  @PostMapping("/api/orders")
  public ResponseEntity<OrderResult> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "X-Correlation-Id", required = false) String suppliedCorrelation,
      @RequestBody(required = false) OrderRequest request) {
    String correlationId = correlationId(suppliedCorrelation);
    DirectUserAuthorizer.DirectPrincipal principal;
    try {
      principal = authorizer.authorize(authorization, evalSandbox, properties.requiredPermission());
    } catch (CatalogException exception) {
      OrderCategory category =
          exception.status() == 403 ? OrderCategory.AUTHORIZATION : OrderCategory.AUTHENTICATION;
      throw new OrderException(
          exception.status(), category, "Direct-user order authorization failed", correlationId);
    }
    OrderResult result =
        service.create(principal.subject(), idempotencyKey, request, correlationId);
    return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
        .body(result);
  }

  private static String correlationId(String supplied) {
    return supplied != null && SAFE_CORRELATION.matcher(supplied).matches()
        ? supplied
        : UUID.randomUUID().toString();
  }
}

@RestControllerAdvice
final class OrderExceptionHandler {
  @ExceptionHandler(OrderException.class)
  ResponseEntity<OrderError> handle(OrderException exception) {
    return ResponseEntity.status(exception.status())
        .body(
            new OrderError(
                exception.category().name(), exception.getMessage(), exception.correlationId()));
  }

  record OrderError(String category, String message, String correlationId) {}
}
