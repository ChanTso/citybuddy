package io.citybuddy.commerce.retail;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

public final class RetailFulfillmentModels {
  private RetailFulfillmentModels() {}

  public record EstimateRequest(List<EstimateItem> items) {
    public EstimateRequest {
      if (items == null || items.size() > 100 || items.stream().anyMatch(item -> item == null)) {
        throw new IllegalArgumentException("items must be an array of at most 100 SKU quantities");
      }
      items = List.copyOf(items);
    }
  }

  public record EstimateItem(String productId, int quantity) {
    public EstimateItem {
      if (productId == null || productId.isBlank() || productId.length() > 64) {
        throw new IllegalArgumentException("productId must contain between 1 and 64 characters");
      }
      if (quantity < 1 || quantity > 24) {
        throw new IllegalArgumentException("quantity must be between 1 and 24");
      }
    }
  }

  public record DeliveryEstimate(
      Instant quotedAt,
      long configVersion,
      String currency,
      String timeZone,
      long itemSubtotalMinor,
      List<QuotedItem> items,
      boolean estimateOnly,
      List<DeliveryOption> options) {}

  public record QuotedItem(
      String productId, int quantity, long unitPriceMinor, long productVersion) {}

  public record DeliveryOption(
      String code,
      String method,
      long feeMinor,
      LocalDate earliestDate,
      LocalDate latestDate,
      Instant readyAt,
      String location) {}

  public record Configuration(long version, String currency, String timeZone, Rules rules) {
    public Configuration {
      if (version < 1 || currency == null || !currency.matches("[A-Z]{3}") || rules == null) {
        throw new IllegalArgumentException("Invalid retail fulfillment configuration");
      }
      ZoneId.of(timeZone);
    }
  }

  public record Rules(Standard standard, Express express, Freight freight, Pickup pickup) {
    public Rules {
      if (standard == null || express == null || freight == null || pickup == null) {
        throw new IllegalArgumentException("All retail fulfillment rules are required");
      }
    }
  }

  public record Standard(
      long feeMinor, long freeOverMinor, int minBusinessDays, int maxBusinessDays) {
    public Standard {
      amounts(feeMinor, freeOverMinor);
      days(minBusinessDays, maxBusinessDays);
    }
  }

  public record Express(long feeMinor, long memberFreeOverMinor, int businessDays) {
    public Express {
      amounts(feeMinor, memberFreeOverMinor);
      days(businessDays, businessDays);
    }
  }

  public record Freight(
      long feeMinor,
      int minBusinessDays,
      int maxBusinessDays,
      Set<String> categories,
      long unitPriceOverMinor) {
    public Freight {
      amounts(feeMinor, unitPriceOverMinor);
      days(minBusinessDays, maxBusinessDays);
      if (categories == null
          || categories.isEmpty()
          || categories.stream().anyMatch(category -> category == null || category.isBlank())) {
        throw new IllegalArgumentException("Freight categories must contain nonblank names");
      }
      categories = Set.copyOf(categories);
    }
  }

  public record Pickup(
      String location, LocalTime opensAt, LocalTime closesAt, int preparationMinutes) {
    public Pickup {
      if (location == null
          || location.isBlank()
          || location.length() > 160
          || opensAt == null
          || closesAt == null
          || !closesAt.isAfter(opensAt)
          || preparationMinutes < 1
          || preparationMinutes > ChronoUnit.MINUTES.between(opensAt, closesAt)) {
        throw new IllegalArgumentException(
            "Pickup preparation must fit within daily opening hours");
      }
    }
  }

  private static void amounts(long fee, long threshold) {
    if (fee < 0 || threshold < 0) {
      throw new IllegalArgumentException("Delivery fees and thresholds cannot be negative");
    }
  }

  private static void days(int first, int last) {
    if (first < 1 || last < first || last > 365) {
      throw new IllegalArgumentException(
          "Delivery estimates require 1 to 365 ordered business days");
    }
  }
}
