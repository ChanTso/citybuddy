package io.citybuddy.commerce.shopping;

import io.citybuddy.commerce.shopping.ShoppingOrderModels.OrderView;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class ShoppingOrderService {
  private final ShoppingOrderRepository repository;

  public ShoppingOrderService(ShoppingOrderRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<OrderView> list(String owner, int limit) {
    return repository.list(owner, limit);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Optional<OrderView> find(String owner, String orderId) {
    return repository.find(owner, orderId);
  }
}
