package io.citybuddy.commerce.refund;

public record RefundRequest(Long amountMinor, String currency, String userSubject) {}
