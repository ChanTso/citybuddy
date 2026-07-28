package io.citybuddy.commerce.action;

import java.time.Instant;

public record ActionReceiptView(
    String receiptId,
    String pendingActionId,
    String actionType,
    String status,
    String orderId,
    String refundId,
    long resourceVersion,
    long amountMinor,
    String currency,
    Instant committedAt,
    boolean replayed) {}
