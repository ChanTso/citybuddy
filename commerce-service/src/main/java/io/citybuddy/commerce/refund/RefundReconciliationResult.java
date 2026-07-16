package io.citybuddy.commerce.refund;

import java.util.List;

public record RefundReconciliationResult(
    String paymentAttemptId,
    String orderId,
    Outcome outcome,
    long refundedAmountMinor,
    List<String> contradictions) {
  public RefundReconciliationResult {
    contradictions = List.copyOf(contradictions);
  }

  public enum Outcome {
    CONSISTENT,
    CONVERGED,
    CONTRADICTION
  }
}
