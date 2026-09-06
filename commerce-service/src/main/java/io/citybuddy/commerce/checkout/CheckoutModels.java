package io.citybuddy.commerce.checkout;

import io.citybuddy.commerce.shopping.ShoppingOrderModels.OrderView;
import java.time.Instant;
import java.util.List;

public final class CheckoutModels {
  private CheckoutModels() {}

  public record Item(
      String productId, int quantity, long expectedProductVersion, long expectedUnitPriceMinor) {}

  public record Command(long expectedCartVersion, String currency, List<Item> items) {}

  public record View(
      String checkoutId,
      long sourceCartVersion,
      String currency,
      long totalMinor,
      Instant createdAt,
      String paymentStatus,
      List<OrderView> orders,
      boolean replayed) {}
}
