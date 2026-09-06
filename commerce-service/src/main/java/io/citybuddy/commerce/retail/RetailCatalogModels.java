package io.citybuddy.commerce.retail;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RetailCatalogModels {
  private RetailCatalogModels() {}

  public record Search(
      String query,
      String category,
      Long minPriceMinor,
      Long maxPriceMinor,
      Double minRating,
      String currency,
      Map<String, String> attributes,
      String sort,
      Integer limit,
      Integer offset) {
    public Search(
        String query,
        String category,
        Long minPriceMinor,
        Long maxPriceMinor,
        Double minRating,
        String currency,
        Map<String, String> attributes,
        String sort,
        Integer limit) {
      this(
          query,
          category,
          minPriceMinor,
          maxPriceMinor,
          minRating,
          currency,
          attributes,
          sort,
          limit,
          0);
    }

    public Search {
      query = text(query, 256, "query");
      category = text(category, 100, "category");
      currency = text(currency == null ? null : currency.trim(), 3, "currency");
      if (currency != null) {
        currency = currency.toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
          throw new IllegalArgumentException("currency must be a three-letter code");
        }
      }
      sort = sort == null ? "relevance" : sort;
      if (!Set.of("relevance", "price_asc", "price_desc", "rating").contains(sort)) {
        throw new IllegalArgumentException("Unsupported retail sort");
      }
      limit = limit == null ? 20 : limit;
      if (limit < 1 || limit > 50) {
        throw new IllegalArgumentException("limit must be between 1 and 50");
      }
      offset = offset == null ? 0 : offset;
      if (offset < 0 || offset > 10_000) {
        throw new IllegalArgumentException("offset must be between 0 and 10000");
      }
      if ((minPriceMinor != null && minPriceMinor < 0)
          || (maxPriceMinor != null && maxPriceMinor < 0)
          || (minPriceMinor != null && maxPriceMinor != null && minPriceMinor > maxPriceMinor)) {
        throw new IllegalArgumentException("Invalid price range");
      }
      if (minRating != null && (!Double.isFinite(minRating) || minRating < 0 || minRating > 5)) {
        throw new IllegalArgumentException("minRating must be between 0 and 5");
      }
      if (currency == null
          && (minPriceMinor != null || maxPriceMinor != null || sort.startsWith("price_"))) {
        throw new IllegalArgumentException("Price filters and sorting require currency");
      }
      if (attributes == null) {
        attributes = Map.of();
      } else {
        if (attributes.size() > 10) {
          throw new IllegalArgumentException("At most ten attributes are supported");
        }
        for (var entry : attributes.entrySet()) {
          requiredText(entry.getKey(), 80, "attribute key");
          requiredText(entry.getValue(), 200, "attribute value");
        }
        attributes = Map.copyOf(attributes);
      }
    }

    private static String text(String value, int maximum, String name) {
      if (value == null) {
        return null;
      }
      if (value.length() > maximum) {
        throw new IllegalArgumentException(name + " is too long");
      }
      String trimmed = value.trim();
      return trimmed.isEmpty() ? null : trimmed;
    }

    private static void requiredText(String value, int maximum, String name) {
      if (text(value, maximum, name) == null) {
        throw new IllegalArgumentException(name + " is required");
      }
    }
  }

  public record Option(String name, List<String> values) {}

  public record View(
      String id,
      String kind,
      String productId,
      String variantOf,
      String title,
      String shortDescription,
      long priceMinor,
      String currency,
      long stockQuantity,
      boolean available,
      boolean inStock,
      Long publicationVersion,
      long metadataVersion,
      Long familyMetadataVersion,
      JsonNode content,
      List<Option> options,
      Map<String, String> optionValues,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<View> variants) {}
}
