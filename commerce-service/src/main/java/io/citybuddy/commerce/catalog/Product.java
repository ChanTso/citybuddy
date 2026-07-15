package io.citybuddy.commerce.catalog;

public record Product(
    String productId,
    String name,
    String description,
    long priceMinor,
    String currency,
    long stockQuantity,
    boolean available,
    long publicationVersion) {}
