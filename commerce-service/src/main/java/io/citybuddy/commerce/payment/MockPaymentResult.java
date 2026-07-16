package io.citybuddy.commerce.payment;

public record MockPaymentResult(
    String attemptId,
    String callbackCorrelationId,
    String orderId,
    String orderKind,
    long amountMinor,
    String currency,
    String state,
    boolean replayed) {}
