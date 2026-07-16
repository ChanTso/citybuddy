package io.citybuddy.commerce.payment;

public record MockPaymentRequest(Long amountMinor, String currency, String userSubject) {}
