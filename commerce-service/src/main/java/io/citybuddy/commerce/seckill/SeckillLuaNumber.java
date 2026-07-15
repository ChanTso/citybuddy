package io.citybuddy.commerce.seckill;

final class SeckillLuaNumber {
  static final long MAX_EXACT_INTEGER = 99_999_999_999_999L;

  private SeckillLuaNumber() {}

  static void requirePositiveExact(long value, String label) {
    if (value < 1) {
      throw new IllegalArgumentException(label + " must be positive");
    }
    if (value > MAX_EXACT_INTEGER) {
      throw new IllegalArgumentException(label + " exceeds the exact Redis Lua integer range");
    }
  }

  static void requireNonNegativeExact(long value, String label) {
    if (value < 0) {
      throw new IllegalArgumentException(label + " must not be negative");
    }
    if (value > MAX_EXACT_INTEGER) {
      throw new IllegalArgumentException(label + " exceeds the exact Redis Lua integer range");
    }
  }
}
