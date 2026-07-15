package io.citybuddy.commerce.catalog;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ProductInvalidationHandler implements CatalogEventHandler {
  private final ProductRepository repository;
  private final ProductCache cache;

  public ProductInvalidationHandler(ProductRepository repository, ProductCache cache) {
    this.repository = repository;
    this.cache = cache;
  }

  @Override
  public void handle(String payload) {
    ProductRepository.CatalogEvent event = repository.parseEvent(payload);
    validate(event);
    long currentGeneration = repository.catalogGeneration();
    if (event.catalogGeneration() > currentGeneration) {
      throw new IllegalStateException("Catalog event is newer than MySQL truth");
    }
    var current = repository.findPublished(event.productId());
    if (current.isPresent()) {
      Product product = current.get();
      if (event.productVersion() > product.publicationVersion()) {
        throw new IllegalStateException("Product event is newer than MySQL truth");
      }
      cache.put(product, currentGeneration);
    } else {
      cache.evict(event.productId());
    }
    List<String> publishedIds = repository.publishedIds();
    cache.rebuildBloom(currentGeneration, publishedIds);
  }

  private static void validate(ProductRepository.CatalogEvent event) {
    try {
      UUID.fromString(event.eventId());
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new IllegalArgumentException("Catalog event has an invalid event id", exception);
    }
    if (event.productId() == null
        || event.productId().isBlank()
        || event.productId().length() > 64
        || event.productVersion() < 1
        || event.catalogGeneration() < 1
        || event.publicationState() == null
        || !Set.of("PUBLISHED", "UNPUBLISHED").contains(event.publicationState())) {
      throw new IllegalArgumentException("Catalog event violates the product event contract");
    }
  }
}
