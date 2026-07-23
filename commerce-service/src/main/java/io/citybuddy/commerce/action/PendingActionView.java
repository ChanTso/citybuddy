package io.citybuddy.commerce.action;

import java.time.Instant;

public record PendingActionView(
    String pendingActionId,
    String actionType,
    String orderId,
    long amountMinor,
    String currency,
    String state,
    Instant expiresAt,
    boolean replayed) {}
