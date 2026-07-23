package io.citybuddy.commerce.payment;

final class MockPaymentIntegrityException extends RuntimeException {
  MockPaymentIntegrityException(String message) {
    super(message);
  }
}
