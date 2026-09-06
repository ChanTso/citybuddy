package io.citybuddy.commerce.shopping;

import java.time.Instant;
import java.util.List;

public final class ShoppingOrderModels {
  private ShoppingOrderModels() {}

  public record ProductSnapshot(
      String productId,
      String name,
      long unitPriceMinor,
      String currency,
      long quantity,
      long totalPriceMinor,
      Long productVersion) {}

  public record PaymentFacts(
      String attemptId,
      String state,
      long stateVersion,
      long amountMinor,
      long refundedAmountMinor,
      String currency,
      Instant succeededAt) {}

  public record RefundStateTotals(
      String state, long count, long requestedAmountMinor, long refundedAmountMinor) {}

  public record RefundFacts(long reservedAmountMinor, List<RefundStateTotals> byState) {}

  public record OrderView(
      String orderKind,
      String orderId,
      String status,
      long stateVersion,
      Instant createdAt,
      Instant unpaidDeadline,
      ProductSnapshot product,
      PaymentFacts payment,
      RefundFacts refunds) {}
}
