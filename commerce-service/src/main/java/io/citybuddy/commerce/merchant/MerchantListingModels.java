package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.databind.JsonNode;
import io.citybuddy.commerce.retail.RetailCatalogModels.Option;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MerchantListingModels {
  private MerchantListingModels() {}

  public record Search(
      String query,
      String status,
      String category,
      Long maxStock,
      String contentQuality,
      String currency,
      String sort,
      Integer limit,
      Integer offset) {
    public Search {
      query = text(query, 256, "query");
      category = text(category, 100, "category");
      currency = text(currency, 3, "currency");
      if (currency != null) {
        currency = currency.toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
          throw invalid("currency must be a three-letter code");
        }
      }
      if (status != null && !Set.of("active", "paused", "draft", "out_of_stock").contains(status)) {
        throw invalid("Unsupported listing status");
      }
      if (contentQuality != null
          && !Set.of("good", "needs_work", "poor").contains(contentQuality)) {
        throw invalid("Unsupported content quality");
      }
      if (maxStock != null && maxStock < 0) {
        throw invalid("maxStock must not be negative");
      }
      sort = sort == null ? "relevance" : sort;
      if (!Set.of("relevance", "stock_asc", "price_asc", "price_desc", "sales_desc")
          .contains(sort)) {
        throw invalid("Unsupported listing sort");
      }
      if (sort.startsWith("price_") && currency == null) {
        throw invalid("Price sorting requires currency");
      }
      limit = limit == null ? 20 : limit;
      offset = offset == null ? 0 : offset;
      page(limit, offset);
    }
  }

  public record Window(Instant start, Instant end, String timeZone) {}

  public record Page<T>(List<T> items, Integer nextOffset, Window window) {}

  public record Operations(
      Long unitCostMinor,
      long lowStockThreshold,
      String contentQuality,
      List<String> missingAttributes,
      long factsVersion,
      Instant observedAt,
      String sourceRef) {}

  public record Sales(
      long orderCount,
      long units,
      long refundRequestedOrderCount,
      BigDecimal refundRequestedOrderPct) {}

  public record Listing(
      String id,
      String kind,
      String variantOf,
      String title,
      String shortDescription,
      long priceMinor,
      String currency,
      long stockQuantity,
      boolean available,
      String publicationState,
      String status,
      Long publicationVersion,
      long metadataVersion,
      Long familyMetadataVersion,
      JsonNode content,
      List<Option> options,
      Map<String, String> optionValues,
      String contentQuality,
      Operations operations,
      Sales salesLast30d,
      BigDecimal marginPct,
      boolean priceEditable,
      Window window,
      List<Listing> variants) {}

  public record InventoryAlert(
      String listingId,
      String title,
      String kind,
      String variantOf,
      Map<String, String> optionValues,
      long stock,
      long threshold,
      long salesLast30d,
      BigDecimal daysOfCover,
      boolean storefrontVisible) {}

  static String text(String value, int maximum, String name) {
    if (value == null) {
      return null;
    }
    if (value.length() > maximum) {
      throw invalid(name + " is too long");
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  static void page(int limit, int offset) {
    if (limit < 1 || limit > 50 || offset < 0 || offset > 10_000) {
      throw invalid("limit must be 1..50 and offset 0..10000");
    }
  }

  private static MerchantException invalid(String message) {
    return new MerchantException(400, "VALIDATION", message);
  }
}
