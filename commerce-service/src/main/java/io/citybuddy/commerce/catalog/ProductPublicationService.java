package io.citybuddy.commerce.catalog;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
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
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Product publication requires transaction synchronization");
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              cache.evict(draft.productId());
            } catch (DataAccessException exception) {
              LOGGER.warn(
                  "Product publication committed; best-effort cache deletion failed for {}",
                  draft.productId(),
                  exception);
            }
          }
        });
    return publication;
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
