package io.citybuddy.commerce.shopping;

import java.util.Map;

public final class ShoppingPreferencesModels {
  private ShoppingPreferencesModels() {}

  public record Preferences(
      String userId,
      String displayName,
      String loyaltyTier,
      String defaultLocation,
      Map<String, String> preferences) {
    public Preferences {
      preferences = Map.copyOf(preferences);
    }
  }
}
