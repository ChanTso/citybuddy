package io.citybuddy.commerce.action;

public record ActionRequestContext(
    String userSubject,
    String supportSessionId,
    String traceId,
    String turnId,
    String sandboxId,
    String requiredScope) {}
