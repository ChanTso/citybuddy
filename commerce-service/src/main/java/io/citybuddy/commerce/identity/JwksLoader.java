package io.citybuddy.commerce.identity;

@FunctionalInterface
public interface JwksLoader {
  String load();
}
