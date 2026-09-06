package io.citybuddy.commerce.cart;

import java.util.List;
import java.util.Map;

public final class CartModels {
  private CartModels() {}

  public record Item(
      String productId,
      int quantity,
      String name,
      long unitPriceMinor,
      String currency,
      long productVersion,
      long stockQuantity,
      boolean available,
      String publicationState,
      Long lineTotalMinor,
      boolean orderable,
      String imageUrl,
      Map<String, String> optionValues,
      String familyId) {}

  public record CartView(
      long version, String currency, Long subtotalMinor, boolean checkoutReady, List<Item> items) {}

  public record Receipt(
      String key,
      String operation,
      String productId,
      int beforeQuantity,
      int afterQuantity,
      long appliedVersion) {}

  public record Result(Receipt receipt, CartView cart, boolean replayed) {}
}
