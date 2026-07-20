package io.citybuddy.commerce.evaluation;

import java.util.Optional;

public enum EvaluationAuditEntityType {
  PRODUCT_FIXTURE,
  PAYMENT_CALLBACK;

  public static Optional<EvaluationAuditEntityType> fromStored(String value) {
    try {
      return Optional.of(EvaluationAuditEntityType.valueOf(value));
    } catch (IllegalArgumentException | NullPointerException exception) {
      return Optional.empty();
    }
  }
}
