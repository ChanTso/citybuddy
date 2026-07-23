package io.citybuddy.commerce.knowledge;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "citybuddy.knowledge-snapshot.enabled", havingValue = "true")
public class KnowledgeSnapshotController {
  private final KnowledgeSnapshotAuthenticator authenticator;
  private final KnowledgeSnapshotService service;

  public KnowledgeSnapshotController(
      KnowledgeSnapshotAuthenticator authenticator, KnowledgeSnapshotService service) {
    this.authenticator = authenticator;
    this.service = service;
  }

  @GetMapping("/internal/knowledge/snapshot")
  public Map<String, Object> snapshot(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    authenticator.authenticate(authorization);
    return service.capture();
  }

  @ExceptionHandler(KnowledgeSnapshotException.class)
  ResponseEntity<Map<String, String>> rejected(KnowledgeSnapshotException exception) {
    return ResponseEntity.status(exception.status()).body(Map.of("error", exception.code()));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<Map<String, String>> unavailable(DataAccessException exception) {
    return ResponseEntity.status(503).body(Map.of("error", "snapshot_source_unavailable"));
  }
}
