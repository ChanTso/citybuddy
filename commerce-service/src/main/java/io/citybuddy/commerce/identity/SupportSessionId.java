package io.citybuddy.commerce.identity;

import java.util.regex.Pattern;

public final class SupportSessionId {
  private static final Pattern LEGACY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");
  private static final Pattern OPAQUE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

  private SupportSessionId() {}

  public static boolean isValid(String value) {
    return value != null && (LEGACY.matcher(value).matches() || OPAQUE.matcher(value).matches());
  }
}
