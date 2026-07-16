package io.citybuddy.commerce.refund;

public record RefundResult(
    String refundId,
    String orderId,
    String orderKind,
    String paymentAttemptId,
    long eligibleAmountMinor,
    long requestedAmountMinor,
    long refundedAmountMinor,
    String currency,
    String state,
    long stateVersion,
    String failureCode,
    boolean replayed) {}
