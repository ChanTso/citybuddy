package io.citybuddy.commerce.evaluation;

import java.util.List;

public record EvaluationResetRequest(
    String sandboxId,
    String caseCorrelation,
    int ttlSeconds,
    String testUserLabel,
    List<ProductFixture> products) {

  public record ProductFixture(
      String productId,
      String name,
      String description,
      long priceMinor,
      String currency,
      long stockQuantity,
      boolean available) {}
}
