package io.citybuddy.commerce.action;

public record PrepareActionCommand(
    String actionType, String orderId, Long amountMinor, String currency) {}
