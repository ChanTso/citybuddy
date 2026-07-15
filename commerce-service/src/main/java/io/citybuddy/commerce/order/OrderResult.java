package io.citybuddy.commerce.order;

public record OrderResult(
    String orderId,
    String productId,
    String productName,
    long unitPriceMinor,
    String currency,
    int quantity,
    long totalPriceMinor,
    long productVersion,
    String status,
    String correlationId,
    boolean replayed) {

  OrderResult asReplay() {
    return new OrderResult(
        orderId,
        productId,
        productName,
        unitPriceMinor,
        currency,
        quantity,
        totalPriceMinor,
        productVersion,
        status,
        correlationId,
        true);
  }
}
