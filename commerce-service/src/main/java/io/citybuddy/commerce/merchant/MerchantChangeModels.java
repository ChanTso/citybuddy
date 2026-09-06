package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public final class MerchantChangeModels {
  private MerchantChangeModels() {}

  public record Command(String kind, JsonNode payload) {}

  public record View(
      String changeId,
      String kind,
      String state,
      String currency,
      JsonNode items,
      JsonNode payload,
      JsonNode result,
      Instant createdAt,
      Instant resolvedAt) {}

  public record Stored(
      String operatorSubject, String sessionId, String intentHash, JsonNode snapshot, View view) {}
}
