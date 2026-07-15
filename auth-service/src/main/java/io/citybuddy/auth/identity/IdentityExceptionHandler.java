package io.citybuddy.auth.identity;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@ConditionalOnProperty(name = "citybuddy.identity.enabled", havingValue = "true")
public final class IdentityExceptionHandler {

  @ExceptionHandler(IdentityException.class)
  ResponseEntity<Map<String, String>> identityFailure(IdentityException exception) {
    return ResponseEntity.status(exception.status())
        .body(Map.of("error", safeMessage(exception.status())));
  }

  @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
  ResponseEntity<Map<String, String>> malformedRequest(Exception exception) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid request"));
  }

  private static String safeMessage(int status) {
    return status == HttpStatus.FORBIDDEN.value() ? "Forbidden" : "Unauthorized";
  }
}
