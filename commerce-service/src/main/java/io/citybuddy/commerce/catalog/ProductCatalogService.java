package io.citybuddy.commerce.catalog;

import java.util.List;
import java.util.Optional;

public final class ProductCatalogService {
  private final ProductRepository repository;
  private final ProductCache cache;

  public ProductCatalogService(ProductRepository repository, ProductCache cache) {
    this.repository = repository;
    this.cache = cache;
  }

  public List<Product> listPublished() {
    return repository.listPublished();
  }

  public Optional<Product> findPublished(String productId) {
    if (productId == null || productId.isBlank()) {
      return Optional.empty();
    }
    long generation = repository.catalogGeneration();
    Optional<Product> cached =
        cache.resolve(productId, generation, () -> repository.findPublished(productId));
    if (cached.isEmpty()) {
      return Optional.empty();
    }
    Product product = cached.get();
    Optional<ProductRepository.LiveFields> live = repository.findPublishedLiveFields(productId);
    if (live.isEmpty()) {
      cache.evict(productId, generation);
      return Optional.empty();
    }
    ProductRepository.LiveFields fields = live.get();
    if (!productId.equals(product.productId())
        || fields.publicationVersion() != product.publicationVersion()) {
      cache.evict(productId, generation);
      Optional<Product> authoritative = repository.findPublished(productId);
      authoritative.ifPresent(value -> cache.put(value, generation));
      return authoritative;
    }
    return Optional.of(
        new Product(
            product.productId(),
            product.name(),
            product.description(),
            fields.priceMinor(),
            fields.currency(),
            fields.stockQuantity(),
            fields.available(),
            fields.publicationVersion()));
  }
}
