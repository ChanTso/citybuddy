package io.citybuddy.commerce.payment;

/** Signals contradictory, incomplete, or non-unique durable committed-payment truth. */
public final class CommittedPaymentIntegrityException extends RuntimeException {
  CommittedPaymentIntegrityException(String message) {
    super(message);
  }
}
