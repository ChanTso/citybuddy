package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public final class MerchantMarketingModels {
  private MerchantMarketingModels() {}

  public record Campaign(
      String campaignId,
      String name,
      String objective,
      String audience,
      String copyText,
      String channel,
      String currency,
      Long budgetMinor,
      Instant startsAt,
      Instant endsAt,
      String state,
      long version,
      Instant createdAt,
      Instant updatedAt,
      String sourceChangeId,
      Long spendMinor,
      Long revenueMinor,
      String observationSourceKind,
      String observationSourceRef,
      Instant observedAt,
      Instant observationStart,
      Instant observationEnd,
      String fixtureVersion) {}

  public record Promotion(
      String promotionId,
      String name,
      String currency,
      int discountBasisPoints,
      Instant startsAt,
      Instant endsAt,
      String state,
      long version,
      Instant createdAt,
      Instant updatedAt,
      Instant appliedAt,
      String sourceChangeId,
      List<PromotionTarget> targets) {}

  public record PromotionTarget(
      String productId,
      long approvedBasePriceMinor,
      long promotionPriceMinor,
      long beforeVersion,
      long afterVersion,
      String eventId,
      long currentPriceMinor,
      String currentCurrency,
      long currentVersion,
      boolean overridden) {}

  public record PreparedOperation(String currency, JsonNode items, JsonNode snapshot) {}

  public record PromotionSnapshot(
      String name,
      int discountBasisPoints,
      Instant startsAt,
      Instant endsAt,
      String currency,
      List<ProductTarget> products,
      List<FamilyTarget> families) {}

  public record ProductTarget(
      String productId,
      String name,
      long priceMinor,
      long targetPriceMinor,
      String currency,
      long version) {}

  public record FamilyTarget(String familyId, long version, List<String> members) {}

  public record CampaignSnapshot(
      String campaignId,
      long expectedVersion,
      String name,
      String objective,
      String audience,
      String copyText,
      String channel,
      String currency,
      Long budgetMinor,
      Instant startsAt,
      Instant endsAt,
      String state) {}
}
