package io.citybuddy.commerce.action;

import com.fasterxml.jackson.annotation.JsonFormat;
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
    @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
            timezone = "UTC")
        Instant committedAt,
    boolean replayed) {}
