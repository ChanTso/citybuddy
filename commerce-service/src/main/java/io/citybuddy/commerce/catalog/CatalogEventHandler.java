package io.citybuddy.commerce.catalog;

@FunctionalInterface
public interface CatalogEventHandler {
  void handle(String payload);
}
