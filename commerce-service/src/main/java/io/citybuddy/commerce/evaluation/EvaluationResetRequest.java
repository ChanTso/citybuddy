package io.citybuddy.commerce.evaluation;

import java.util.List;

public record EvaluationResetRequest(
    String sandboxId,
    String caseCorrelation,
    int ttlSeconds,
    String testUserLabel,
    List<ProductFixture> products,
    PaymentOrderFixture paymentOrder) {

  public record ProductFixture(
      String productId,
      String name,
      String description,
      long priceMinor,
      String currency,
      long stockQuantity,
      boolean available) {}

  public record PaymentOrderFixture(String orderId, String productId, int quantity) {}
}
