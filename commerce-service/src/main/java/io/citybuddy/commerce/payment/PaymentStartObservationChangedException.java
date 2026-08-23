package io.citybuddy.commerce.payment;

final class PaymentStartObservationChangedException extends RuntimeException {
  PaymentStartObservationChangedException(String message) {
    super(message);
  }
}
