package io.citybuddy.commerce.action;

import com.fasterxml.jackson.annotation.JsonFormat;
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
    @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
            timezone = "UTC")
        Instant expiresAt,
    boolean replayed) {}
