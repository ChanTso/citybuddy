package io.citybuddy.commerce.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.cart.CartConfiguration;
import io.citybuddy.commerce.cart.CartModels;
import io.citybuddy.commerce.cart.CartRepository;
import io.citybuddy.commerce.cart.CartService;
import io.citybuddy.commerce.order.BatchOrderService;
import io.citybuddy.commerce.order.OrderConfiguration;
import io.citybuddy.commerce.order.OrderException;
import io.citybuddy.commerce.order.OrderProperties;
import io.citybuddy.commerce.order.OrderRepository;
import io.citybuddy.commerce.order.OrderRequest;
import io.citybuddy.commerce.order.OrderService;
import io.citybuddy.commerce.payment.MockPaymentCallbackRequest;
import io.citybuddy.commerce.payment.MockPaymentRepository;
import io.citybuddy.commerce.payment.MockPaymentRequest;
import io.citybuddy.commerce.payment.MockPaymentResult;
import io.citybuddy.commerce.payment.MockPaymentService;
import io.citybuddy.commerce.shopping.ShoppingOrderConfiguration;
import io.citybuddy.commerce.shopping.ShoppingOrderModels.OrderView;
import io.citybuddy.commerce.shopping.ShoppingOrderRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(
    classes = CheckoutIntegrationTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CheckoutIntegrationTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({
    OrderConfiguration.class,
    CartConfiguration.class,
    CheckoutConfiguration.class,
    ShoppingOrderConfiguration.class
  })
  static class Application {}

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("citybuddy.orders.enabled", () -> "true");
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private CartService carts;
  @Autowired private CartRepository cartRepository;
  @Autowired private CheckoutRepository checkoutRepository;
  @Autowired private ShoppingOrderRepository shoppingOrders;
  @Autowired private BatchOrderService checkouts;
  @Autowired private OrderService singleOrders;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper mapper;
  private JdbcTemplate root;
  private String owner;
  private String prefix;
  private MockPaymentService payments;

  @BeforeEach
  void fixture() {
    prefix = "checkout-" + UUID.randomUUID().toString().substring(0, 8);
    owner = prefix + "-owner";
    root =
        new JdbcTemplate(
            new DriverManagerDataSource(
                required("CATALOG_MYSQL_URL"), "root", required("MYSQL_BOOTSTRAP_PASSWORD")));
    payments =
        new MockPaymentService(
            new MockPaymentRepository(jdbc),
            new TransactionTemplate(transactionManager),
            Clock.systemUTC());
  }

  @Test
  void committedWholeCartReplaysAndPaymentCanResumeAfterPartialCompletion() {
    String first = product("a", 1250, 20);
    String second = product("b", 750, 20);
    carts.add(owner, "add-a", first, 2);
    carts.add(owner, "add-b", second, 3);
    CheckoutModels.Command quote = quote(owner);
    CheckoutModels.View created = checkouts.create(owner, "确认:1", quote, "create");
    assertThat(created.orders()).hasSize(2);
    assertThat(created.totalMinor()).isEqualTo(4750);
    assertThat(created.paymentStatus()).isEqualTo("UNPAID");
    assertThat(carts.get(owner).items()).isEmpty();
    assertThat(carts.get(owner).version()).isEqualTo(quote.expectedCartVersion() + 1);
    assertCount(owner, 2);
    assertThat(stock(first)).isEqualTo(18);
    assertThat(stock(second)).isEqualTo(17);

    carts.add(owner, "later-cart", first, 1);
    CheckoutModels.View replay = checkouts.create(owner, "确认:1", quote, "lost-response-retry");
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.checkoutId()).isEqualTo(created.checkoutId());
    assertThat(replay.orders()).isEqualTo(created.orders());
    assertThat(carts.get(owner).items())
        .singleElement()
        .satisfies(item -> assertThat(item.quantity()).isEqualTo(1));
    assertThat(checkouts.find(owner.toUpperCase(Locale.ROOT), created.checkoutId())).isEmpty();
    assertThatThrownBy(() -> checkouts.create(owner, "确认:1", quote(owner), "new-intent"))
        .isInstanceOfSatisfying(
            CheckoutException.class,
            e -> assertThat(e.category()).isEqualTo("idempotency_conflict"));

    OrderView firstOrder = created.orders().getFirst();
    MockPaymentResult firstAttempt = start(firstOrder);
    assertThat(firstAttempt.state()).isEqualTo("PENDING");
    assertThat(checkouts.find(owner, created.checkoutId()).orElseThrow().paymentStatus())
        .isEqualTo("UNPAID");
    MockPaymentCallbackRequest firstCallback = callback(firstAttempt);
    payments.callback("callback:" + firstOrder.orderId(), firstCallback);
    // The second transport has not confirmed payment; persisted facts must retain the partial
    // state.
    OrderView secondOrder = created.orders().get(1);
    MockPaymentResult secondAttempt = start(secondOrder);
    CheckoutModels.View interrupted = checkouts.find(owner, created.checkoutId()).orElseThrow();
    assertThat(interrupted.paymentStatus()).isEqualTo("PARTIALLY_PAID");
    assertThat(interrupted.orders().get(1).payment().state()).isEqualTo("PENDING");
    assertThat(start(firstOrder).attemptId()).isEqualTo(firstAttempt.attemptId());
    assertThat(payments.callback("callback:" + firstOrder.orderId(), firstCallback).replayed())
        .isTrue();
    assertThat(start(secondOrder).attemptId()).isEqualTo(secondAttempt.attemptId());
    MockPaymentCallbackRequest secondCallback = callback(secondAttempt);
    payments.callback("callback:" + secondOrder.orderId(), secondCallback);
    payments.callback("callback:" + secondOrder.orderId(), secondCallback);
    CheckoutModels.View paid = checkouts.find(owner, created.checkoutId()).orElseThrow();
    assertThat(paid.paymentStatus()).isEqualTo("PAID");
    assertThat(paid.orders())
        .allSatisfy(
            order -> {
              assertThat(order.status()).isEqualTo("PAID");
              assertThat(order.payment().state()).isEqualTo("SUCCEEDED");
              assertThat(
                      jdbc.queryForObject(
                          "SELECT COUNT(*) FROM inventory_ledger WHERE order_id=? AND movement_type='STANDARD_PAYMENT'",
                          Long.class,
                          order.orderId()))
                  .isEqualTo(1);
            });
    assertCount(owner, 2);

    String origin =
        jdbc.queryForObject(
            "SELECT idempotency_key FROM order_idempotency WHERE user_subject=? AND order_id=?",
            String.class,
            owner,
            firstOrder.orderId());
    OrderRequest oldRequest = new OrderRequest();
    oldRequest.setProductId(firstOrder.product().productId());
    oldRequest.setQuantity((int) firstOrder.product().quantity());
    oldRequest.setExpectedProductVersion(firstOrder.product().productVersion());
    assertThat(singleOrders.create(owner, origin, oldRequest, "legacy-replay").orderId())
        .isEqualTo(firstOrder.orderId());
    oldRequest.setQuantity(1);
    assertThatThrownBy(() -> singleOrders.create(owner, origin, oldRequest, "legacy-conflict"))
        .isInstanceOf(OrderException.class);
    assertThat(stock(first)).isEqualTo(18);
  }

  @Test
  void oneInvalidLineRollsBackEarlierOrdersOriginsOutboxAndCartClear() {
    for (String failure :
        List.of("price", "zero-price", "version", "stock", "unpublished", "currency")) {
      String subject = owner + "-" + failure;
      String first = product(failure + "-a", 100, 10);
      String last = product(failure + "-z", 200, 10);
      carts.add(subject, "first", first, 1);
      carts.add(subject, "last", last, 2);
      CheckoutModels.Command quote = quote(subject);
      String update =
          switch (failure) {
            case "price" -> "price_minor=201";
            case "zero-price" -> "price_minor=0";
            case "version" -> "publication_version=2";
            case "stock" -> "stock_quantity=1";
            case "unpublished" -> "publication_state='UNPUBLISHED'";
            case "currency" -> "currency='USD'";
            default -> throw new AssertionError();
          };
      root.update("UPDATE product SET " + update + " WHERE product_id=?", last);
      assertThatThrownBy(() -> checkouts.create(subject, "checkout", quote, failure))
          .isInstanceOf(CheckoutException.class);
      assertCount(subject, 0);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM shopping_checkout WHERE user_subject=?",
                  Long.class,
                  subject))
          .isZero();
      assertThat(stock(first)).isEqualTo(10);
      assertThat(carts.get(subject).version()).isEqualTo(quote.expectedCartVersion());
      assertThat(carts.get(subject).items()).hasSize(2);
    }
  }

  @Test
  void sameKeyCompetitionAndOppositeInputOrderCreateOneBatchPerAccount() throws Exception {
    String first = product("a", 100, 30);
    String second = product("b", 200, 30);
    String other = owner + "-other";
    for (String subject : List.of(owner, other)) {
      carts.add(subject, "a", first, 1);
      carts.add(subject, "b", second, 1);
    }
    CheckoutModels.Command forward = quote(owner);
    var reversed = new ArrayList<>(quote(other).items());
    Collections.reverse(reversed);
    CheckoutModels.Command backward =
        new CheckoutModels.Command(quote(other).expectedCartVersion(), "CNY", reversed);
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(3)) {
      var one =
          pool.submit(
              () -> {
                start.await();
                return checkouts.create(owner, "same", forward, "one");
              });
      var retry =
          pool.submit(
              () -> {
                start.await();
                return checkouts.create(owner, "same", forward, "retry");
              });
      var two =
          pool.submit(
              () -> {
                start.await();
                return checkouts.create(other, "same", backward, "two");
              });
      start.countDown();
      assertThat(one.get(20, TimeUnit.SECONDS).checkoutId())
          .isEqualTo(retry.get(20, TimeUnit.SECONDS).checkoutId());
      assertThat(two.get(20, TimeUnit.SECONDS).orders()).hasSize(2);
    }
    assertCount(owner, 2);
    assertCount(other, 2);
    assertThat(stock(first)).isEqualTo(28);
    assertThat(stock(second)).isEqualTo(28);
  }

  @Test
  void deadlockAfterAChildWriteRetriesTheWholeTransaction() {
    String first = product("a", 100, 10);
    String second = product("b", 200, 10);
    carts.add(owner, "a", first, 1);
    carts.add(owner, "b", second, 1);
    AtomicInteger injected = new AtomicInteger();
    OrderRepository failingOnce =
        new OrderRepository(jdbc, mapper) {
          @Override
          public void insertOutbox(String orderId, ProductSnapshot product, int quantity) {
            super.insertOutbox(orderId, product, quantity);
            if (injected.getAndIncrement() == 0) {
              throw new CannotAcquireLockException(
                  "Injected 1213 after first child write",
                  new SQLException("Deadlock found when trying to get lock", "40001", 1213));
            }
          }
        };
    BatchOrderService service =
        new BatchOrderService(
            failingOnce,
            cartRepository,
            checkoutRepository,
            shoppingOrders,
            new TransactionTemplate(transactionManager),
            new OrderProperties(null, 0, 0, 0));
    CheckoutModels.Command quote = quote(owner);
    assertThat(service.create(owner, "deadlock", quote, "deadlock").orders()).hasSize(2);
    assertThat(injected.get()).isEqualTo(3);
    assertCount(owner, 2);
    assertThat(stock(first)).isEqualTo(9);
    assertThat(stock(second)).isEqualTo(9);
    assertThat(carts.get(owner).version()).isEqualTo(quote.expectedCartVersion() + 1);
  }

  @Test
  void maximumCartCreatesOneHundredPaymentCompatibleOriginsAtomically() {
    for (int index = 0; index < 100; index++) {
      carts.add(owner, "add:" + index, product("item-" + index, 100 + index, 10), 1);
    }
    CheckoutModels.Command quote = quote(owner);
    CheckoutModels.View created = checkouts.create(owner, "maximum", quote, "maximum");
    assertThat(created.orders()).hasSize(100);
    assertThat(created.totalMinor()).isEqualTo(14950);
    assertCount(owner, 100);
    assertThat(start(created.orders().getLast()).state()).isEqualTo("PENDING");
    assertThat(carts.get(owner).items()).isEmpty();
  }

  private String product(String suffix, long price, long stock) {
    String id = prefix + "-" + suffix;
    root.update(
        """
        INSERT INTO product (product_id,name,description,price_minor,currency,stock_quantity,
          available,publication_state,publication_version)
        VALUES (?,?,'Checkout fixture',?,'CNY',?,TRUE,'PUBLISHED',1)
        """,
        id,
        id,
        price,
        stock);
    return id;
  }

  private CheckoutModels.Command quote(String subject) {
    CartModels.CartView cart = carts.get(subject);
    return new CheckoutModels.Command(
        cart.version(),
        cart.currency(),
        cart.items().stream()
            .map(
                item ->
                    new CheckoutModels.Item(
                        item.productId(),
                        item.quantity(),
                        item.productVersion(),
                        item.unitPriceMinor()))
            .toList());
  }

  private MockPaymentResult start(OrderView order) {
    return payments.start(
        owner,
        order.orderId(),
        "checkout-pay:" + order.orderId(),
        new MockPaymentRequest(
            order.product().totalPriceMinor(), order.product().currency(), null));
  }

  private MockPaymentCallbackRequest callback(MockPaymentResult attempt) {
    return new MockPaymentCallbackRequest(
        UUID.randomUUID().toString(),
        attempt.callbackCorrelationId(),
        attempt.orderId(),
        attempt.amountMinor(),
        attempt.currency(),
        "SUCCEEDED",
        null,
        null,
        null,
        null);
  }

  private void assertCount(String subject, long expected) {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM standard_order WHERE BINARY user_subject=BINARY ?",
                Long.class,
                subject))
        .isEqualTo(expected);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_idempotency WHERE BINARY user_subject=BINARY ?",
                Long.class,
                subject))
        .isEqualTo(expected);
    assertThat(
            jdbc.queryForObject(
                """
        SELECT COUNT(*) FROM commerce_outbox b JOIN standard_order o ON b.aggregate_id=o.order_id
        WHERE BINARY o.user_subject=BINARY ? AND b.event_type='STANDARD_ORDER_CREATED'
        """,
                Long.class,
                subject))
        .isEqualTo(expected);
  }

  private long stock(String id) {
    return jdbc.queryForObject(
        "SELECT stock_quantity FROM product WHERE product_id=?", Long.class, id);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }
}
