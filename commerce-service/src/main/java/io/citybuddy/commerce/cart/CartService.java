package io.citybuddy.commerce.cart;

import io.citybuddy.commerce.cart.CartModels.CartView;
import io.citybuddy.commerce.cart.CartModels.Item;
import io.citybuddy.commerce.cart.CartModels.Receipt;
import io.citybuddy.commerce.cart.CartModels.Result;
import io.citybuddy.commerce.cart.CartRepository.Product;
import io.citybuddy.commerce.mysql.BoundedMySqlTransactions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

public final class CartService {
  private static final int MAXIMUM_QUANTITY = 24;
  private static final int MAXIMUM_LINES = 100;
  private final CartRepository repository;
  private final BoundedMySqlTransactions transactions;

  public CartService(CartRepository repository, BoundedMySqlTransactions transactions) {
    this.repository = repository;
    this.transactions = transactions;
  }

  public CartView get(String owner) {
    return transact(() -> view(owner), false);
  }

  public Optional<Result> command(String owner, String key) {
    validateKey(key);
    return transact(
        () ->
            repository
                .findCommand(owner, key)
                .map(command -> new Result(command.receipt(), view(owner), true)),
        false);
  }

  public Result add(String owner, String key, String productId, int quantity) {
    validateQuantity(quantity);
    return mutate(owner, key, productId, "ADD", quantity, -1);
  }

  public Result set(
      String owner, String key, String productId, int quantity, long expectedCartVersion) {
    validateQuantity(quantity);
    validateVersion(expectedCartVersion);
    return mutate(owner, key, productId, "SET", quantity, expectedCartVersion);
  }

  public Result remove(String owner, String key, String productId, long expectedCartVersion) {
    validateVersion(expectedCartVersion);
    return mutate(owner, key, productId, "REMOVE", 0, expectedCartVersion);
  }

  private Result mutate(
      String owner, String key, String productId, String operation, int quantity, long expected) {
    validateKey(key);
    if (productId == null || productId.isBlank() || productId.length() > 64) {
      throw invalid("Product identifier is required and must not exceed 64 characters");
    }
    return transact(() -> mutateOnce(owner, key, productId, operation, quantity, expected), true);
  }

  private Result mutateOnce(
      String owner, String key, String productId, String operation, int quantity, long expected) {
    long version = repository.lockCart(owner);
    String hash = intentHash(operation, productId, quantity, expected);
    var previous = repository.findCommand(owner, key);
    if (previous.isPresent()) {
      if (!previous.get().intentHash().equals(hash)) {
        throw new CartException(409, "IDEMPOTENCY_CONFLICT", "Cart command key has another intent");
      }
      return new Result(previous.get().receipt(), view(owner), true);
    }
    if (!operation.equals("ADD") && expected != version) {
      throw new CartException(409, "VERSION_CONFLICT", "Cart changed; refresh before editing it");
    }
    var product = repository.findProduct(productId, !operation.equals("REMOVE"));
    String canonicalId = product.map(Product::productId).orElse(productId);
    List<CartRepository.Line> lines = repository.lines(owner);
    int before =
        lines.stream()
            .filter(line -> line.productId().equals(canonicalId))
            .mapToInt(CartRepository.Line::quantity)
            .findFirst()
            .orElse(0);
    int after =
        switch (operation) {
          case "ADD" -> before + quantity;
          case "SET" -> before == 0 ? 0 : quantity;
          case "REMOVE" -> 0;
          default -> throw new IllegalStateException("Unsupported cart command");
        };
    if (after != before) {
      if (after > 0) {
        if (after > MAXIMUM_QUANTITY) {
          throw new CartException(409, "QUANTITY_LIMIT", "Cart SKU quantity cannot exceed 24");
        }
        if (before == 0 && lines.size() >= MAXIMUM_LINES) {
          throw new CartException(409, "CART_LIMIT", "Cart cannot contain more than 100 SKUs");
        }
        Product sku =
            product.orElseThrow(
                () ->
                    new CartException(409, "NOT_ORDERABLE", "An actual orderable SKU is required"));
        if (!sku.available()
            || !sku.publicationState().equals("PUBLISHED")
            || sku.priceMinor() < 1
            || sku.stockQuantity() < after) {
          throw new CartException(
              409,
              "NOT_ORDERABLE",
              "SKU is unavailable, has no payable price, or has insufficient stock");
        }
        for (var line : repository.currentLines(owner)) {
          if (!line.product().currency().equals(sku.currency())) {
            throw new CartException(409, "CURRENCY_CONFLICT", "Cart SKUs must use one currency");
          }
        }
        if (lineTotal(sku.priceMinor(), after) == null) {
          throw new CartException(409, "AMOUNT_LIMIT", "Cart line amount is too large");
        }
        repository.saveQuantity(owner, canonicalId, after);
      } else {
        repository.remove(owner, canonicalId);
      }
      version = repository.advance(owner);
    }
    Receipt receipt = new Receipt(key, operation, canonicalId, before, after, version);
    repository.insertCommand(owner, hash, receipt);
    return new Result(receipt, view(owner), false);
  }

  private CartView view(String owner) {
    long version = repository.version(owner);
    var lines = repository.currentLines(owner);
    List<Item> items = new ArrayList<>();
    String currency = lines.isEmpty() ? null : lines.getFirst().product().currency();
    boolean oneCurrency = true;
    boolean ready = !lines.isEmpty();
    Long subtotal = 0L;
    for (var line : lines) {
      Product product = line.product();
      Long total = lineTotal(product.priceMinor(), line.quantity());
      boolean orderable =
          product.available()
              && product.publicationState().equals("PUBLISHED")
              && product.priceMinor() > 0
              && product.stockQuantity() >= line.quantity()
              && total != null;
      ready &= orderable;
      oneCurrency &= product.currency().equals(currency);
      if (subtotal != null && total != null) {
        try {
          subtotal = Math.addExact(subtotal, total);
        } catch (ArithmeticException exception) {
          subtotal = null;
        }
      } else {
        subtotal = null;
      }
      items.add(
          new Item(
              product.productId(),
              line.quantity(),
              product.name(),
              product.priceMinor(),
              product.currency(),
              product.productVersion(),
              product.stockQuantity(),
              product.available(),
              product.publicationState(),
              total,
              orderable,
              line.imageUrl(),
              line.optionValues(),
              line.familyId()));
    }
    if (!oneCurrency) {
      currency = null;
      subtotal = null;
    }
    return new CartView(version, currency, subtotal, ready && subtotal != null, List.copyOf(items));
  }

  private <T> T transact(Supplier<T> work, boolean retry) {
    int attempts = retry ? 3 : 1;
    for (int attempt = 1; ; attempt++) {
      try {
        return transactions.execute(work);
      } catch (PessimisticLockingFailureException exception) {
        if (attempt >= attempts) {
          throw new CartException(
              503, "RETRYABLE_CONCURRENCY", "Cart result is unconfirmed; retry the same key");
        }
      } catch (DataAccessResourceFailureException | CannotCreateTransactionException exception) {
        throw new CartException(
            503, "UNAVAILABLE", "Cart result is unconfirmed; retry the same key");
      }
    }
  }

  private static Long lineTotal(long price, int quantity) {
    try {
      return Math.multiplyExact(price, quantity);
    } catch (ArithmeticException exception) {
      return null;
    }
  }

  static String intentHash(String operation, String productId, int quantity, long expected) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String field :
          List.of(
              "CART_COMMAND_V1",
              operation,
              productId,
              Integer.toString(quantity),
              Long.toString(expected))) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void validateKey(String key) {
    if (key == null || key.isBlank() || key.length() > 128) {
      throw invalid("Idempotency key is required and must not exceed 128 characters");
    }
  }

  private static void validateQuantity(int quantity) {
    if (quantity < 1 || quantity > MAXIMUM_QUANTITY) {
      throw invalid("Quantity must be between 1 and 24");
    }
  }

  private static void validateVersion(long version) {
    if (version < 0) {
      throw invalid("Expected cart version must not be negative");
    }
  }

  private static CartException invalid(String message) {
    return new CartException(400, "VALIDATION", message);
  }
}
