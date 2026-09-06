package io.citybuddy.commerce.retail;

import io.citybuddy.commerce.retail.RetailCatalogModels.Search;
import io.citybuddy.commerce.retail.RetailCatalogModels.View;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class RetailCatalogService {
  private final RetailCatalogRepository repository;

  public RetailCatalogService(RetailCatalogRepository repository) {
    this.repository = repository;
  }

  // Selection and hydration must observe the same prices and published variant membership.
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<View> search(Search request) {
    return repository.search(request);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Optional<View> find(String id) {
    return repository.find(id);
  }
}
