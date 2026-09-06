package io.citybuddy.commerce.order;

import io.citybuddy.commerce.cart.CartRepository;
import io.citybuddy.commerce.checkout.CheckoutException;
import io.citybuddy.commerce.checkout.CheckoutModels;
import io.citybuddy.commerce.checkout.CheckoutRepository;
import io.citybuddy.commerce.shopping.ShoppingOrderModels.OrderView;
import io.citybuddy.commerce.shopping.ShoppingOrderRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class BatchOrderService {
  private final OrderRepository orders;
  private final CartRepository carts;
  private final CheckoutRepository checkouts;
  private final ShoppingOrderRepository shoppingOrders;
  private final OrderTransactions writes;
  private final TransactionTemplate reads;
  private final OrderProperties properties;

  public BatchOrderService(
      OrderRepository orders,
      CartRepository carts,
      CheckoutRepository checkouts,
      ShoppingOrderRepository shoppingOrders,
      TransactionTemplate transactions,
      OrderProperties properties) {
    this.orders = orders;
    this.carts = carts;
    this.checkouts = checkouts;
    this.shoppingOrders = shoppingOrders;
    this.properties = properties;
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.writes = new OrderTransactions(orders, transactions, properties.lockWaitTimeoutSeconds());
    this.reads = new TransactionTemplate(transactions.getTransactionManager());
    reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    reads.setReadOnly(true);
  }

  public CheckoutModels.View create(
      String owner, String key, CheckoutModels.Command command, String correlationId) {
    Intent intent = validate(key, command);
    for (int attempt = 1; attempt <= properties.maximumConcurrencyAttempts(); attempt++) {
      try {
        return writes.mutate(status -> createOnce(owner, key, intent, correlationId));
      } catch (PessimisticLockingFailureException
          | OrderRepository.IdempotencyRaceException
          | OrderService.StockRaceException
          | DuplicateKeyException exception) {
        if (attempt == properties.maximumConcurrencyAttempts()) {
          return observeCommitted(owner, key, intent);
        }
      } catch (DataAccessResourceFailureException | CannotCreateTransactionException exception) {
        throw unavailable();
      } catch (OrderException exception) {
        String category =
            switch (exception.category()) {
              case STALE_VERSION -> "stale_quote";
              case INSUFFICIENT_STOCK -> "insufficient_stock";
              case VALIDATION -> "validation";
              default -> throw exception;
            };
        throw new CheckoutException(exception.status(), category, exception.getMessage());
      }
    }
    throw new IllegalStateException("Unreachable checkout retry state");
  }

  public Optional<CheckoutModels.View> find(String owner, String checkoutId) {
    return reads.execute(
        status -> checkouts.find(owner, checkoutId).map(receipt -> view(owner, receipt, false)));
  }

  private CheckoutModels.View createOnce(
      String owner, String key, Intent intent, String correlationId) {
    long cartVersion = carts.lockCart(owner);
    Optional<CheckoutRepository.Receipt> committed = checkouts.findByKey(owner, key);
    if (committed.isPresent()) {
      return replay(owner, committed.get(), intent);
    }
    if (cartVersion != intent.command().expectedCartVersion()) {
      throw new CheckoutException(409, "stale_cart", "Cart changed; confirm a fresh quote");
    }
    List<CartRepository.Line> lines = carts.lines(owner);
    Map<String, Integer> quantities =
        lines.stream()
            .collect(
                Collectors.toMap(CartRepository.Line::productId, CartRepository.Line::quantity));
    if (lines.size() != intent.command().items().size()
        || intent.command().items().stream()
            .anyMatch(
                item ->
                    !Integer.valueOf(item.quantity()).equals(quantities.get(item.productId())))) {
      throw new CheckoutException(
          409, "stale_cart", "Quote must contain the complete current cart");
    }
    String checkoutId = UUID.randomUUID().toString();
    checkouts.insert(
        checkoutId,
        owner,
        key,
        intent.hash(),
        cartVersion,
        intent.command().currency(),
        intent.totalMinor());
    List<Child> children = new ArrayList<>();
    int number = 0;
    // Reserve origins before taking product locks, as the single-order path does.
    for (CheckoutModels.Item item : intent.command().items()) {
      String orderId = UUID.randomUUID().toString();
      int line = ++number;
      orders.reserveIdempotency(
          owner,
          "checkout:" + checkoutId + ":" + line,
          StandardOrderIntentCommitment.hash(
              item.productId(), item.quantity(), item.expectedProductVersion()),
          orderId);
      children.add(new Child(line, orderId, item));
    }
    // Individual sorted locking reads share the merchant price-change lock order.
    for (Child child : children) {
      CheckoutModels.Item item = child.item();
      OrderRepository.ProductSnapshot product =
          orders
              .lockProduct(item.productId())
              .orElseThrow(
                  () -> new CheckoutException(422, "validation", "Product is not orderable"));
      if (!product.currency().equals(intent.command().currency())
          || product.priceMinor() != item.expectedUnitPriceMinor()) {
        throw new CheckoutException(409, "stale_quote", "Price changed; confirm a fresh quote");
      }
      StandardOrderWriter.write(
          orders,
          owner,
          child.orderId(),
          product,
          item.quantity(),
          item.expectedProductVersion(),
          correlationId);
      checkouts.attach(checkoutId, child.number(), child.orderId());
    }
    carts.clearAndAdvance(owner);
    return view(owner, checkouts.find(owner, checkoutId).orElseThrow(), false);
  }

  private CheckoutModels.View observeCommitted(String owner, String key, Intent intent) {
    try {
      return reads.execute(
          status ->
              checkouts
                  .findByKey(owner, key)
                  .map(receipt -> replay(owner, receipt, intent))
                  .orElseThrow(
                      () ->
                          new CheckoutException(
                              429,
                              "retryable_concurrency",
                              "Checkout result is unconfirmed; retry the same idempotency key")));
    } catch (PessimisticLockingFailureException exception) {
      throw new CheckoutException(
          429,
          "retryable_concurrency",
          "Checkout result is unconfirmed; retry the same idempotency key");
    } catch (DataAccessResourceFailureException | CannotCreateTransactionException exception) {
      throw unavailable();
    }
  }

  private CheckoutModels.View replay(
      String owner, CheckoutRepository.Receipt receipt, Intent intent) {
    if (!receipt.intentHash().equals(intent.hash())) {
      throw new CheckoutException(
          409,
          "idempotency_conflict",
          "Idempotency key is already bound to a different checkout quote");
    }
    return view(owner, receipt, true);
  }

  private CheckoutModels.View view(
      String owner, CheckoutRepository.Receipt receipt, boolean replayed) {
    List<String> ids = checkouts.orderIds(receipt.checkoutId());
    Map<String, OrderView> byId =
        shoppingOrders.findStandardOrders(owner, ids).stream()
            .collect(Collectors.toMap(OrderView::orderId, Function.identity()));
    if (ids.isEmpty() || ids.size() != byId.size()) {
      throw new IllegalStateException("Checkout is missing its owned orders");
    }
    List<OrderView> children = ids.stream().map(byId::get).toList();
    long total = 0;
    long paid = 0;
    for (OrderView child : children) {
      if (!receipt.currency().equals(child.product().currency())) {
        throw new IllegalStateException("Checkout currency conflicts with its orders");
      }
      total = Math.addExact(total, child.product().totalPriceMinor());
      if (child.status().equals("PAID")) {
        paid++;
      }
    }
    if (total != receipt.totalMinor()) {
      throw new IllegalStateException("Checkout total conflicts with its orders");
    }
    return new CheckoutModels.View(
        receipt.checkoutId(),
        receipt.sourceCartVersion(),
        receipt.currency(),
        receipt.totalMinor(),
        receipt.createdAt(),
        paid == 0 ? "UNPAID" : paid == children.size() ? "PAID" : "PARTIALLY_PAID",
        children,
        replayed);
  }

  private Intent validate(String key, CheckoutModels.Command command) {
    if (key == null
        || key.isBlank()
        || key.length() > 128
        || command == null
        || command.expectedCartVersion() < 0
        || command.currency() == null
        || !command.currency().matches("[A-Z]{3}")
        || command.items() == null
        || command.items().isEmpty()
        || command.items().size() > 100) {
      throw new CheckoutException(400, "validation", "Checkout quote is invalid");
    }
    var unique = new HashSet<String>();
    var normalized = new ArrayList<CheckoutModels.Item>();
    long total = 0;
    for (CheckoutModels.Item item : command.items()) {
      if (item == null
          || item.productId() == null
          || item.productId().isBlank()
          || item.productId().length() > 64
          || item.quantity() < 1
          || item.quantity() > Math.min(24, properties.maximumQuantity())
          || item.expectedProductVersion() < 1
          || item.expectedUnitPriceMinor() < 1) {
        throw new CheckoutException(400, "validation", "Checkout item is invalid");
      }
      String id = item.productId().strip();
      if (!unique.add(id)) {
        throw new CheckoutException(
            400, "validation", "Checkout cannot contain duplicate products");
      }
      normalized.add(
          new CheckoutModels.Item(
              id, item.quantity(), item.expectedProductVersion(), item.expectedUnitPriceMinor()));
      try {
        total =
            Math.addExact(
                total, Math.multiplyExact(item.quantity(), item.expectedUnitPriceMinor()));
      } catch (ArithmeticException exception) {
        throw new CheckoutException(400, "validation", "Checkout total is out of range");
      }
    }
    normalized.sort(Comparator.comparing(CheckoutModels.Item::productId));
    var canonical =
        new StringBuilder("SHOPPING_CHECKOUT_V1:")
            .append(command.expectedCartVersion())
            .append(':')
            .append(command.currency());
    for (CheckoutModels.Item item : normalized) {
      canonical
          .append(':')
          .append(item.productId().length())
          .append(':')
          .append(item.productId())
          .append(':')
          .append(item.quantity())
          .append(':')
          .append(item.expectedProductVersion())
          .append(':')
          .append(item.expectedUnitPriceMinor());
    }
    try {
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
      return new Intent(
          new CheckoutModels.Command(
              command.expectedCartVersion(), command.currency(), List.copyOf(normalized)),
          hash,
          total);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static CheckoutException unavailable() {
    return new CheckoutException(
        503,
        "unavailable",
        "Checkout database is unavailable; retry the same idempotency key to confirm the result");
  }

  private record Intent(CheckoutModels.Command command, String hash, long totalMinor) {}

  private record Child(int number, String orderId, CheckoutModels.Item item) {}
}
