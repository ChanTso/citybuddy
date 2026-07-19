package io.citybuddy.commerce.payment;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public final class MockPaymentCallbackRequest {
  private String callbackEventId;
  private String callbackCorrelationId;
  private String orderId;
  private Long amountMinor;
  private String currency;
  private String outcome;
  private String sandboxId;
  private String supportSessionId;
  private String traceId;
  private String operationId;
  private boolean hasExtraFields;

  public MockPaymentCallbackRequest() {}

  public MockPaymentCallbackRequest(
      String callbackEventId,
      String callbackCorrelationId,
      String orderId,
      Long amountMinor,
      String currency,
      String outcome) {
    this(
        callbackEventId,
        callbackCorrelationId,
        orderId,
        amountMinor,
        currency,
        outcome,
        null,
        null,
        null,
        null);
  }

  public MockPaymentCallbackRequest(
      String callbackEventId,
      String callbackCorrelationId,
      String orderId,
      Long amountMinor,
      String currency,
      String outcome,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId) {
    this.callbackEventId = callbackEventId;
    this.callbackCorrelationId = callbackCorrelationId;
    this.orderId = orderId;
    this.amountMinor = amountMinor;
    this.currency = currency;
    this.outcome = outcome;
    this.sandboxId = sandboxId;
    this.supportSessionId = supportSessionId;
    this.traceId = traceId;
    this.operationId = operationId;
  }

  public String getCallbackEventId() {
    return callbackEventId;
  }

  public void setCallbackEventId(String callbackEventId) {
    this.callbackEventId = callbackEventId;
  }

  public String getCallbackCorrelationId() {
    return callbackCorrelationId;
  }

  public void setCallbackCorrelationId(String callbackCorrelationId) {
    this.callbackCorrelationId = callbackCorrelationId;
  }

  public String getOrderId() {
    return orderId;
  }

  public void setOrderId(String orderId) {
    this.orderId = orderId;
  }

  public Long getAmountMinor() {
    return amountMinor;
  }

  public void setAmountMinor(Long amountMinor) {
    this.amountMinor = amountMinor;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getOutcome() {
    return outcome;
  }

  public void setOutcome(String outcome) {
    this.outcome = outcome;
  }

  public String getSandboxId() {
    return sandboxId;
  }

  public void setSandboxId(String sandboxId) {
    this.sandboxId = sandboxId;
  }

  public String getSupportSessionId() {
    return supportSessionId;
  }

  public void setSupportSessionId(String supportSessionId) {
    this.supportSessionId = supportSessionId;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public String getOperationId() {
    return operationId;
  }

  public void setOperationId(String operationId) {
    this.operationId = operationId;
  }

  @JsonAnySetter
  public void captureExtra(String name, Object value) {
    hasExtraFields = true;
  }

  public String callbackEventId() {
    return callbackEventId;
  }

  public String callbackCorrelationId() {
    return callbackCorrelationId;
  }

  public String orderId() {
    return orderId;
  }

  public Long amountMinor() {
    return amountMinor;
  }

  public String currency() {
    return currency;
  }

  public String outcome() {
    return outcome;
  }

  public String sandboxId() {
    return sandboxId;
  }

  public String supportSessionId() {
    return supportSessionId;
  }

  public String traceId() {
    return traceId;
  }

  public String operationId() {
    return operationId;
  }

  public boolean hasExtraFields() {
    return hasExtraFields;
  }
}
