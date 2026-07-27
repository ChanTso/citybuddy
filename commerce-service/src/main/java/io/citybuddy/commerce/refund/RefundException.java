package io.citybuddy.commerce.refund;

import java.util.Set;

public final class RefundException extends RuntimeException {
  private static final Set<Integer> PUBLIC_REJECTION_STATUSES =
      Set.of(400, 401, 403, 404, 409, 429, 503);

  private final int status;
  private final String category;
  private final RefundRejectionReason reason;

  public RefundException(int status, String category, String message) {
    this(status, category, RefundRejectionReason.NOT_APPLICABLE, message);
  }

  public RefundException(
      int status, String category, RefundRejectionReason reason, String message) {
    super(message);
    if (!PUBLIC_REJECTION_STATUSES.contains(status)) {
      throw new IllegalArgumentException(
          "Refund rejection status is not part of the public contract");
    }
    if (!reason.permits(status)) {
      throw new IllegalArgumentException(
          "Refund rejection reason does not match its public status");
    }
    this.status = status;
    this.category = category;
    this.reason = reason;
  }

  public int status() {
    return status;
  }

  public String category() {
    return category;
  }

  public RefundRejectionReason reason() {
    return reason;
  }

  static Set<Integer> publicRejectionStatuses() {
    return PUBLIC_REJECTION_STATUSES;
  }
}
