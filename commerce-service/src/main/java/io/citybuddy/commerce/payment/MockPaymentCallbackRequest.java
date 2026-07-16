package io.citybuddy.commerce.payment;

public record MockPaymentCallbackRequest(
    String callbackEventId,
    String callbackCorrelationId,
    String orderId,
    Long amountMinor,
    String currency,
    String outcome) {}
