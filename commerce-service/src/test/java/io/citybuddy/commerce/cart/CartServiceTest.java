package io.citybuddy.commerce.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.citybuddy.commerce.cart.CartModels.Receipt;
import io.citybuddy.commerce.cart.CartRepository.CurrentLine;
import io.citybuddy.commerce.cart.CartRepository.Line;
import io.citybuddy.commerce.cart.CartRepository.Product;
import io.citybuddy.commerce.cart.CartRepository.StoredCommand;
import io.citybuddy.commerce.mysql.BoundedMySqlTransactions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class CartServiceTest {
  private final CartRepository repository = mock(CartRepository.class);
  private final BoundedMySqlTransactions transactions = mock(BoundedMySqlTransactions.class);
  private final CartService service = new CartService(repository, transactions);

  @BeforeEach
  void setup() {
    when(transactions.execute(any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
  }

  @Test
  void absentCartAndCommandReadsNeverCreateTheAccountRoot() {
    var cart = service.get("buyer");
    assertThat(cart.version()).isZero();
    assertThat(cart.items()).isEmpty();
    assertThat(cart.subtotalMinor()).isZero();
    assertThat(cart.checkoutReady()).isFalse();
    assertThat(service.command("buyer", "missing")).isEmpty();
    verify(repository, never()).lockCart(anyString());
    verify(repository, never()).insertCommand(anyString(), anyString(), any());
  }

  @Test
  void unavailableAndMixedCurrencyLinesStayVisibleWithoutAnInvalidQuote() {
    Product unpublished = new Product("sku", "Shirt", 500, "USD", 3, 0, false, "DRAFT");
    Product otherCurrency = new Product("other", "Other", 700, "CNY", 1, 10, true, "PUBLISHED");
    when(repository.currentLines("buyer"))
        .thenReturn(
            List.of(
                new CurrentLine("sku", 2, unpublished, "shirt.png", Map.of("size", "M"), "shirts"),
                line(otherCurrency, 1)));
    var cart = service.get("buyer");
    assertThat(cart.items()).hasSize(2);
    assertThat(cart.items().getFirst().orderable()).isFalse();
    assertThat(cart.items().getFirst().optionValues()).containsEntry("size", "M");
    assertThat(cart.subtotalMinor()).isNull();
    assertThat(cart.currency()).isNull();
    assertThat(cart.checkoutReady()).isFalse();
  }

  @Test
  void oldRemoveReplaysBeforeVersionCheckAndCannotDeleteAReaddedLine() {
    Receipt receipt = new Receipt("remove", "REMOVE", "sku", 2, 0, 4);
    when(repository.lockCart("buyer")).thenReturn(8L);
    when(repository.version("buyer")).thenReturn(8L);
    when(repository.findCommand("buyer", "remove"))
        .thenReturn(
            Optional.of(new StoredCommand(CartService.intentHash("REMOVE", "SKU", 0, 3), receipt)));
    when(repository.currentLines("buyer")).thenReturn(List.of(line(product(), 6)));
    var result = service.remove("buyer", "remove", "SKU", 3);
    assertThat(result.replayed()).isTrue();
    assertThat(result.receipt()).isEqualTo(receipt);
    assertThat(result.cart().version()).isEqualTo(8);
    assertThat(result.cart().items().getFirst().quantity()).isEqualTo(6);
    verify(repository, never()).remove(anyString(), anyString());
    verify(repository, never()).advance(anyString());
  }

  @Test
  void sameKeyWithAnotherIntentConflictsEvenIfTheCartVersionIsOld() {
    when(repository.findCommand("buyer", "add"))
        .thenReturn(
            Optional.of(
                new StoredCommand(
                    CartService.intentHash("ADD", "sku", 1, -1),
                    new Receipt("add", "ADD", "sku", 0, 1, 1))));
    assertThatThrownBy(() -> service.add("buyer", "add", "sku", 2))
        .isInstanceOfSatisfying(
            CartException.class,
            exception -> assertThat(exception.category()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    verify(repository, never()).saveQuantity(anyString(), anyString(), anyInt());
  }

  @Test
  void setOfMissingItemRecordsANoopWithoutAdvancingVersion() {
    when(repository.lockCart("buyer")).thenReturn(9L);
    when(repository.version("buyer")).thenReturn(9L);
    var result = service.set("buyer", "set-missing", "missing", 2, 9);
    assertThat(result.receipt()).isEqualTo(new Receipt("set-missing", "SET", "missing", 0, 0, 9));
    verify(repository)
        .insertCommand("buyer", CartService.intentHash("SET", "missing", 2, 9), result.receipt());
    verify(repository, never()).advance(anyString());
    verify(repository, never()).saveQuantity(anyString(), anyString(), anyInt());
  }

  @Test
  void missingSkuReceiptKeepsItsIntentWhenTheSkuIsPublishedWithAnotherCanonicalId() {
    Receipt receipt = new Receipt("noop", "SET", "SKU", 0, 0, 0);
    when(repository.findCommand("buyer", "noop"))
        .thenReturn(
            Optional.of(new StoredCommand(CartService.intentHash("SET", "SKU", 2, 0), receipt)));
    when(repository.lockCart("buyer")).thenReturn(3L);
    var result = service.set("buyer", "noop", "SKU", 2, 0);
    assertThat(result.replayed()).isTrue();
    assertThat(result.receipt()).isEqualTo(receipt);
    verify(repository, never()).saveQuantity(anyString(), anyString(), anyInt());
  }

  @Test
  void zeroPriceCannotBeAddedWhileAnExistingLineRemainsVisibleButNotPayable() {
    Product free = new Product("sku", "Shirt", 0, "USD", 4, 100, true, "PUBLISHED");
    when(repository.findProduct("sku", true)).thenReturn(Optional.of(free));
    for (int before : List.of(0, 2)) {
      when(repository.lines("buyer"))
          .thenReturn(before == 0 ? List.of() : List.of(new Line("sku", before)));
      assertThatThrownBy(() -> service.add("buyer", "zero-price", "sku", 1))
          .isInstanceOfSatisfying(
              CartException.class,
              exception -> assertThat(exception.category()).isEqualTo("NOT_ORDERABLE"));
    }
    when(repository.currentLines("buyer")).thenReturn(List.of(line(free, 2)));
    var cart = service.get("buyer");
    assertThat(cart.items()).hasSize(1);
    assertThat(cart.items().getFirst().unitPriceMinor()).isZero();
    assertThat(cart.items().getFirst().orderable()).isFalse();
    assertThat(cart.checkoutReady()).isFalse();
    verify(repository, never()).saveQuantity(anyString(), anyString(), anyInt());
    verify(repository, never()).insertCommand(anyString(), anyString(), any());
  }

  @Test
  void cumulativeAddRejectsTheLimitWithoutSilentlyCapping() {
    when(repository.findProduct("sku", true)).thenReturn(Optional.of(product()));
    when(repository.lines("buyer")).thenReturn(List.of(new Line("sku", 23)));
    assertThatThrownBy(() -> service.add("buyer", "add", "sku", 2))
        .isInstanceOfSatisfying(
            CartException.class,
            exception -> assertThat(exception.category()).isEqualTo("QUANTITY_LIMIT"));
    verify(repository, never()).saveQuantity(anyString(), anyString(), anyInt());
    verify(repository, never()).insertCommand(anyString(), anyString(), any());
  }

  @Test
  void addStoresTheCanonicalSkuAndRetriesTheWholeTransactionAfterALockFailure() {
    when(repository.lockCart("buyer"))
        .thenThrow(new CannotAcquireLockException("lock timeout"))
        .thenReturn(2L);
    when(repository.findProduct("SKU", true)).thenReturn(Optional.of(product()));
    when(repository.lines("buyer")).thenReturn(List.of(new Line("sku", 2)));
    when(repository.advance("buyer")).thenReturn(3L);
    var result = service.add("buyer", " 买家命令 ", "SKU", 3);
    assertThat(result.receipt()).isEqualTo(new Receipt(" 买家命令 ", "ADD", "sku", 2, 5, 3));
    verify(repository).saveQuantity("buyer", "sku", 5);
    verify(transactions, times(2)).execute(any());
  }

  @Test
  void expiredEditCannotOverwriteAndProgrammingFailuresAreNotRetried() {
    when(repository.lockCart("buyer")).thenReturn(3L);
    assertThatThrownBy(() -> service.remove("buyer", "remove", "sku", 2))
        .isInstanceOfSatisfying(
            CartException.class,
            exception -> assertThat(exception.category()).isEqualTo("VERSION_CONFLICT"));
    verify(repository, never()).remove(anyString(), anyString());
    when(repository.lockCart("broken")).thenThrow(new IllegalStateException("bad schema"));
    assertThatThrownBy(() -> service.add("broken", "add", "sku", 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("bad schema");
    verify(repository).lockCart("broken");
  }

  private static Product product() {
    return new Product("sku", "Shirt", 500, "USD", 3, 100, true, "PUBLISHED");
  }

  private static CurrentLine line(Product product, int quantity) {
    return new CurrentLine(product.productId(), quantity, product, null, Map.of(), null);
  }
}
