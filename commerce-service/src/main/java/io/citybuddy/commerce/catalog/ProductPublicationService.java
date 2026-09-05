package io.citybuddy.commerce.catalog;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class ProductPublicationService {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProductPublicationService.class);

  private final ProductRepository repository;
  private final ProductCache cache;

  public ProductPublicationService(ProductRepository repository, ProductCache cache) {
    this.repository = repository;
    this.cache = cache;
  }

  @Transactional
  public ProductRepository.Publication publish(ProductRepository.ProductDraft draft, UUID eventId) {
    validate(draft, eventId);
    ProductRepository.Publication publication = repository.publish(draft, eventId);
    evictAfterCommit(List.of(draft.productId()));
    return publication;
  }

  // Joining callers also use READ_COMMITTED for activity eligibility without activity row locks.
  @Transactional(
      isolation = Isolation.READ_COMMITTED,
      noRollbackFor = ProductPriceChangeException.class)
  public List<ProductRepository.PriceChangeResult> changePrices(
      List<ProductRepository.PriceChange> changes, String currency) {
    if (changes == null
        || changes.isEmpty()
        || changes.size() > 3
        || currency == null
        || !currency.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("Invalid product price changes");
    }
    var productIds = new HashSet<String>();
    for (ProductRepository.PriceChange change : changes) {
      if (change == null
          || !hasText(change.productId())
          || change.productId().length() > 64
          || !change.productId().equals(change.productId().strip())
          || change.expectedVersion() < 1
          || change.newPriceMinor() < 1
          || !productIds.add(change.productId())) {
        throw new IllegalArgumentException("Invalid product price change");
      }
    }
    List<ProductRepository.PriceChangeResult> results = repository.changePrices(changes, currency);
    evictAfterCommit(results.stream().map(ProductRepository.PriceChangeResult::productId).toList());
    return results;
  }

  private void evictAfterCommit(List<String> productIds) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Product publication requires transaction synchronization");
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            for (String productId : productIds) {
              try {
                cache.evict(productId);
              } catch (DataAccessException exception) {
                LOGGER.warn(
                    "Product publication committed; best-effort cache deletion failed for {}",
                    productId,
                    exception);
              }
            }
          }
        });
  }

  private static void validate(ProductRepository.ProductDraft draft, UUID eventId) {
    if (draft == null
        || eventId == null
        || !hasText(draft.productId())
        || !hasText(draft.name())
        || draft.description() == null
        || draft.priceMinor() < 0
        || draft.stockQuantity() < 0
        || draft.currency() == null
        || !draft.currency().matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("Invalid product publication");
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
