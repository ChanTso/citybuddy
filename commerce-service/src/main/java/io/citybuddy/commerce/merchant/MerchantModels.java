package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public final class MerchantModels {
  private MerchantModels() {}

  public record Context(String operatorSubject, String sessionId) {}

  public record PriceInput(String productId, long newPriceMinor) {}

  public record PrepareCommand(String currency, List<PriceInput> items) {}

  public record ProductView(
      String productId,
      String name,
      long priceMinor,
      String currency,
      long publicationVersion,
      long stockQuantity,
      boolean available,
      String publicationState,
      boolean priceEditable) {}

  public record DraftItem(
      String productId,
      String name,
      long oldPriceMinor,
      long newPriceMinor,
      String currency,
      long expectedVersion) {}

  public record DraftView(
      String draftId,
      String currency,
      String state,
      List<DraftItem> items,
      JsonNode result,
      Instant createdAt,
      Instant resolvedAt) {}

  public record StoredDraft(
      String operatorSubject, String sessionId, String intentHash, DraftView view) {}
}
