package io.citybuddy.commerce.payment;

public record MockPaymentCallbackResult(
    String attemptId,
    String callbackCorrelationId,
    String orderId,
    String state,
    boolean replayed) {}
