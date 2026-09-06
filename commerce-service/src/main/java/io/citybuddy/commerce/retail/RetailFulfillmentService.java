package io.citybuddy.commerce.retail;

import io.citybuddy.commerce.retail.RetailFulfillmentModels.DeliveryEstimate;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.DeliveryOption;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.EstimateRequest;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.Pickup;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.QuotedItem;
import io.citybuddy.commerce.shopping.ShoppingPreferencesRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class RetailFulfillmentService {
  private final RetailFulfillmentRepository repository;
  private final ShoppingPreferencesRepository preferences;
  private final Clock clock;

  public RetailFulfillmentService(
      RetailFulfillmentRepository repository,
      ShoppingPreferencesRepository preferences,
      Clock clock) {
    this.repository = repository;
    this.preferences = preferences;
    this.clock = clock;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public DeliveryEstimate estimate(String owner, EstimateRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("A delivery estimate request is required");
    }
    var config = repository.configuration();
    boolean member = "MEMBER".equals(preferences.find(owner).loyaltyTier());
    var rules = config.rules();
    var skus = repository.skus(request.items());
    Set<String> canonical = new HashSet<>();
    List<QuotedItem> quoted = new ArrayList<>();
    long subtotal = 0;
    boolean freight = false;
    for (var sku : skus) {
      var item = request.items().get(sku.itemIndex());
      if (sku.productId() == null) {
        throw unavailable("sku_unavailable", "Delivery requires an existing leaf SKU");
      }
      if (!canonical.add(sku.productId())) {
        throw unavailable("duplicate_sku", "A delivery estimate cannot repeat the same SKU");
      }
      if (!"PUBLISHED".equals(sku.publicationState())
          || !sku.available()
          || sku.priceMinor() < 1
          || sku.stockQuantity() < item.quantity()) {
        throw unavailable(
            "sku_unavailable", "The requested SKU quantity cannot currently be purchased");
      }
      if (!config.currency().equals(sku.currency())) {
        throw unavailable(
            "unsupported_currency", "Delivery estimates do not support this SKU currency");
      }
      try {
        subtotal = Math.addExact(subtotal, Math.multiplyExact(sku.priceMinor(), item.quantity()));
      } catch (ArithmeticException exception) {
        throw unavailable("amount_out_of_range", "The requested amount cannot be represented");
      }
      quoted.add(
          new QuotedItem(
              sku.productId(), item.quantity(), sku.priceMinor(), sku.publicationVersion()));
      freight |=
          sku.category() != null
              && rules.freight().categories().contains(sku.category())
              && sku.priceMinor() > rules.freight().unitPriceOverMinor();
    }
    Instant now = clock.instant();
    ZonedDateTime local = now.atZone(ZoneId.of(config.timeZone()));
    LocalDate today = local.toLocalDate();
    List<DeliveryOption> options = new ArrayList<>();
    options.add(
        new DeliveryOption(
            "STANDARD",
            "delivery",
            subtotal > rules.standard().freeOverMinor() ? 0 : rules.standard().feeMinor(),
            businessDate(today, rules.standard().minBusinessDays()),
            businessDate(today, rules.standard().maxBusinessDays()),
            null,
            null));
    LocalDate expressDate = businessDate(today, rules.express().businessDays());
    options.add(
        new DeliveryOption(
            "EXPRESS",
            "delivery",
            member && subtotal > rules.express().memberFreeOverMinor()
                ? 0
                : rules.express().feeMinor(),
            expressDate,
            expressDate,
            null,
            null));
    if (freight) {
      options.add(
          new DeliveryOption(
              "FREIGHT",
              "shipping",
              rules.freight().feeMinor(),
              businessDate(today, rules.freight().minBusinessDays()),
              businessDate(today, rules.freight().maxBusinessDays()),
              null,
              null));
    }
    options.add(
        new DeliveryOption(
            "PICKUP",
            "pickup",
            0,
            null,
            null,
            pickupReadyAt(local, rules.pickup()),
            rules.pickup().location()));
    return new DeliveryEstimate(
        now,
        config.version(),
        config.currency(),
        config.timeZone(),
        subtotal,
        List.copyOf(quoted),
        true,
        List.copyOf(options));
  }

  private static LocalDate businessDate(LocalDate date, int count) {
    LocalDate result = date;
    for (int remaining = count; remaining > 0; ) {
      result = result.plusDays(1);
      if (result.getDayOfWeek() != DayOfWeek.SATURDAY
          && result.getDayOfWeek() != DayOfWeek.SUNDAY) {
        remaining--;
      }
    }
    return result;
  }

  private static Instant pickupReadyAt(ZonedDateTime now, Pickup pickup) {
    var opens = now.toLocalDate().atTime(pickup.opensAt()).atZone(now.getZone());
    var closes = now.toLocalDate().atTime(pickup.closesAt()).atZone(now.getZone());
    var ready = (now.isBefore(opens) ? opens : now).plusMinutes(pickup.preparationMinutes());
    if (ready.isAfter(closes)) {
      ready = opens.plusDays(1).plusMinutes(pickup.preparationMinutes());
    }
    return ready.toInstant();
  }

  private static RetailFulfillmentException unavailable(String category, String message) {
    return new RetailFulfillmentException(category, message);
  }
}
