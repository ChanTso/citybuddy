package io.citybuddy.commerce.payment;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public final class MockPaymentRequest {
  private Long amountMinor;
  private String currency;
  private String userSubject;
  private boolean hasExtraFields;

  public MockPaymentRequest() {}

  public MockPaymentRequest(Long amountMinor, String currency, String userSubject) {
    this.amountMinor = amountMinor;
    this.currency = currency;
    this.userSubject = userSubject;
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

  public String getUserSubject() {
    return userSubject;
  }

  public void setUserSubject(String userSubject) {
    this.userSubject = userSubject;
  }

  @JsonAnySetter
  public void captureExtra(String name, Object value) {
    hasExtraFields = true;
  }

  public Long amountMinor() {
    return amountMinor;
  }

  public String currency() {
    return currency;
  }

  public String userSubject() {
    return userSubject;
  }

  public boolean hasExtraFields() {
    return hasExtraFields;
  }
}
