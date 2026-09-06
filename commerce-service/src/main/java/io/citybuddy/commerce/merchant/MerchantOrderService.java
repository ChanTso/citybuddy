package io.citybuddy.commerce.merchant;

import io.citybuddy.commerce.shopping.ShoppingOrderModels.OrderView;
import io.citybuddy.commerce.shopping.ShoppingOrderRepository;
import java.util.List;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class MerchantOrderService {
  private final ShoppingOrderRepository repository;

  public MerchantOrderService(ShoppingOrderRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<OrderView> list(int limit) {
    if (limit < 1 || limit > 50) {
      throw new MerchantException(400, "VALIDATION", "Order limit must be between 1 and 50");
    }
    return repository.listForMerchant(limit);
  }
}
