package io.citybuddy.commerce.evaluation;

import io.citybuddy.commerce.cart.CartModels.CartView;
import io.citybuddy.commerce.cart.CartService;
import io.citybuddy.commerce.retail.RetailPolicyModels.Policy;
import io.citybuddy.commerce.retail.RetailPolicyRepository;
import io.citybuddy.commerce.shopping.ShoppingOrderModels.OrderView;
import io.citybuddy.commerce.shopping.ShoppingOrderRepository;
import io.citybuddy.commerce.shopping.ShoppingPreferencesModels.Preferences;
import io.citybuddy.commerce.shopping.ShoppingPreferencesRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class EvaluationShoppingReadService {
  private final ShoppingOrderRepository orders;
  private final ShoppingPreferencesRepository preferences;
  private final CartService cart;
  private final RetailPolicyRepository policies;

  public EvaluationShoppingReadService(
      ShoppingOrderRepository orders,
      ShoppingPreferencesRepository preferences,
      CartService cart,
      RetailPolicyRepository policies) {
    this.orders = orders;
    this.preferences = preferences;
    this.cart = cart;
    this.policies = policies;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<OrderView> list(String owner, String sandbox, int limit) {
    return orders.listForEvaluation(owner, sandbox, limit);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Optional<OrderView> find(String owner, String sandbox, String orderId) {
    return orders.findForEvaluation(owner, sandbox, orderId);
  }

  public Preferences preferences(String owner) {
    return preferences.find(owner);
  }

  public CartView cart(String owner) {
    return cart.get(owner);
  }

  public List<Policy> policies(String query) {
    return policies.search(query);
  }
}
