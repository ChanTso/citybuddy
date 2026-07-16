package io.citybuddy.commerce.seckill;

import io.citybuddy.commerce.catalog.CatalogException;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@ConditionalOnProperty(name = "citybuddy.seckill.order.enabled", havingValue = "true")
public final class SeckillReservationController {
  private final DirectUserAuthorizer authorizer;
  private final SeckillOrderProperties properties;
  private final SeckillTransactionCoordinator coordinator;

  public SeckillReservationController(
      DirectUserAuthorizer authorizer,
      SeckillOrderProperties properties,
      SeckillTransactionCoordinator coordinator) {
    this.authorizer = authorizer;
    this.properties = properties;
    this.coordinator = coordinator;
  }

  @PostMapping("/api/seckill/activities/{activityId}/reservations")
  public ResponseEntity<ReservationResult> reserve(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String activityId,
      @RequestBody(required = false) ReservationRequest request) {
    String subject = authorize(authorization, evalSandbox);
    ReservationResult result = coordinator.submit(subject, activityId, idempotencyKey, request);
    HttpStatus status =
        switch (result.state()) {
          case PENDING -> HttpStatus.ACCEPTED;
          case REJECTED -> HttpStatus.CONFLICT;
          case ADMITTED -> result.replay() ? HttpStatus.OK : HttpStatus.CREATED;
          case ORDERED, CANCELLED -> HttpStatus.OK;
        };
    return ResponseEntity.status(status).body(result);
  }

  @GetMapping("/api/reservations/{reservationId}")
  public ReservationResult poll(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox,
      @PathVariable String reservationId) {
    return coordinator.poll(authorize(authorization, evalSandbox), reservationId);
  }

  private String authorize(String authorization, String evalSandbox) {
    try {
      return authorizer
          .authorize(authorization, evalSandbox, properties.requiredPermission())
          .subject();
    } catch (CatalogException exception) {
      throw new SeckillRequestException(
          exception.status(),
          exception.status() == 403 ? "AUTHORIZATION" : "AUTHENTICATION",
          "Direct-user reservation authorization failed");
    }
  }
}

@RestControllerAdvice(assignableTypes = SeckillReservationController.class)
final class SeckillRequestExceptionHandler {
  @ExceptionHandler(SeckillRequestException.class)
  ResponseEntity<Map<String, String>> handle(SeckillRequestException exception) {
    return ResponseEntity.status(exception.status())
        .body(Map.of("category", exception.category(), "message", exception.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, String>> handleUnreadable() {
    return ResponseEntity.badRequest()
        .body(Map.of("category", "VALIDATION", "message", "Reservation body is invalid"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> handleInvalid(IllegalArgumentException exception) {
    int status = exception.getMessage().contains("missing or not owned") ? 404 : 400;
    return ResponseEntity.status(status)
        .body(Map.of("category", "VALIDATION", "message", exception.getMessage()));
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<Map<String, String>> handleConflict(IllegalStateException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("category", "CONFLICT", "message", exception.getMessage()));
  }
}
