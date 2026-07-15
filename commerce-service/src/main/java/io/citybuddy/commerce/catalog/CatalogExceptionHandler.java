package io.citybuddy.commerce.catalog;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@ConditionalOnProperty(name = "citybuddy.catalog.enabled", havingValue = "true")
public final class CatalogExceptionHandler {

  @ExceptionHandler(CatalogException.class)
  ResponseEntity<Map<String, String>> handle(CatalogException exception) {
    return ResponseEntity.status(exception.status()).body(Map.of("error", exception.getMessage()));
  }
}
