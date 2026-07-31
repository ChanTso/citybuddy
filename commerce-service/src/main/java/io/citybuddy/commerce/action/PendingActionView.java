package io.citybuddy.commerce.action;

import java.time.Instant;

public record PendingActionView(
    String pendingActionId,
    String actionType,
    String userSubject,
    String supportSessionId,
    String traceId,
    String turnId,
    String requiredScope,
    String sandboxId,
    String orderId,
    long targetVersion,
    long amountMinor,
    String currency,
    String state,
    Instant expiresAt,
    boolean replayed) {}
