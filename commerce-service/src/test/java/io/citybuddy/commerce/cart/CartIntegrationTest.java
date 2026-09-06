package io.citybuddy.commerce.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.citybuddy.commerce.cart.CartModels.Result;
import io.citybuddy.commerce.mysql.BoundedMySqlTransactions;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
class CartIntegrationTest {
  private HikariDataSource dataSource;
  private JdbcTemplate jdbc;
  private CartService service;
  private String owner;
  private String sku;

  @BeforeEach
  void setup() {
    var config = new HikariConfig();
    config.setJdbcUrl(required("CATALOG_MYSQL_URL"));
    config.setUsername("commerce_app");
    config.setPassword(required("MYSQL_COMMERCE_APP_PASSWORD"));
    config.setMinimumIdle(0);
    config.setMaximumPoolSize(4);
    dataSource = new HikariDataSource(config);
    jdbc = new JdbcTemplate(dataSource);
    var transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    service =
        new CartService(
            new CartRepository(jdbc, new ObjectMapper()),
            new BoundedMySqlTransactions(jdbc, transactionTemplate, 1));
    owner = "cart-it-" + UUID.randomUUID().toString().substring(0, 8);
    sku = owner + "-sku";
    product(sku, "USD");
  }

  @AfterEach
  void closePool() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @Test
  void concurrentSameKeyAddsOnceAndSeparateCommandsAccumulateCanonicalSku() throws Exception {
    var results =
        concurrently(
            () -> service.add(owner, "same", sku.toUpperCase(Locale.ROOT), 2),
            () -> service.add(owner, "same", sku.toUpperCase(Locale.ROOT), 2));
    assertThat(results).extracting(Result::replayed).containsExactlyInAnyOrder(false, true);
    assertThat(results.getFirst().receipt().productId()).isEqualTo(sku);
    service.add(owner, "another", sku, 3);
    assertThat(quantity()).isEqualTo(5);
    assertThat(count("shopping_cart_command")).isEqualTo(2);
    assertThatThrownBy(() -> service.add(owner, "same", sku, 2))
        .isInstanceOfSatisfying(
            CartException.class,
            exception -> assertThat(exception.category()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    assertThat(quantity()).isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT stock_quantity FROM product WHERE product_id = ?", Long.class, sku))
        .isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM standard_order WHERE BINARY user_subject = BINARY ?",
                Long.class,
                owner))
        .isZero();
  }

  @Test
  void removeReplayCannotDeleteAReaddedItemAndMissingSetIsAPersistentNoop() {
    service.add(owner, "add", sku, 2);
    var removed = service.remove(owner, "remove", sku, 1);
    service.add(owner, "readd", sku, 4);
    var replay = service.remove(owner, "remove", sku, 1);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.receipt()).isEqualTo(removed.receipt());
    assertThat(replay.cart().version()).isEqualTo(3);
    assertThat(quantity()).isEqualTo(4);
    assertThatThrownBy(() -> service.set(owner, "stale", sku, 1, 2))
        .isInstanceOfSatisfying(
            CartException.class,
            exception -> assertThat(exception.category()).isEqualTo("VERSION_CONFLICT"));
    var noop = service.set(owner, "missing", owner + "-missing", 2, 3);
    assertThat(noop.receipt().beforeQuantity()).isZero();
    assertThat(noop.receipt().afterQuantity()).isZero();
    assertThat(noop.receipt().appliedVersion()).isEqualTo(3);
    assertThat(service.command(owner, "remove").orElseThrow().receipt())
        .isEqualTo(removed.receipt());
    assertThat(service.get(owner).version()).isEqualTo(3);
    assertThat(service.command(owner, "stale")).isEmpty();
  }

  @Test
  void missingSkuNoopReplaysAfterPublicationAndCannotEditTheNewLine() {
    String missing = owner + "-new";
    var noop = service.set(owner, "before-publication", missing, 2, 0);
    String canonical = missing.toUpperCase(Locale.ROOT);
    product(canonical, "USD");
    service.add(owner, "after-publication", canonical, 5);
    var replay = service.set(owner, "before-publication", missing, 2, 0);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.receipt()).isEqualTo(noop.receipt());
    assertThat(replay.cart().version()).isEqualTo(1);
    assertThat(replay.cart().items().getFirst().productId()).isEqualTo(canonical);
    assertThat(replay.cart().items().getFirst().quantity()).isEqualTo(5);
    assertThat(count("shopping_cart_command")).isEqualTo(2);
  }

  @Test
  void twoConcurrentAddsCannotCrossTheTwentyFourQuantityBoundary() throws Exception {
    service.add(owner, "initial", sku, 23);
    var outcomes = concurrently(() -> addCategory("last-a", sku), () -> addCategory("last-b", sku));
    assertThat(outcomes).containsExactlyInAnyOrder("OK", "QUANTITY_LIMIT");
    assertThat(quantity()).isEqualTo(24);
    assertThat(count("shopping_cart_command")).isEqualTo(2);
    assertThat(service.get(owner).version()).isEqualTo(2);
  }

  @Test
  void rootLockEnforcesTheHundredLineBoundaryAcrossTwoNewSkus() throws Exception {
    List<Object[]> products = new ArrayList<>();
    List<Object[]> items = new ArrayList<>();
    for (int index = 0; index < 101; index++) {
      String id = owner + "-line-" + index;
      products.add(new Object[] {id, id});
      if (index < 99) {
        items.add(new Object[] {owner, id});
      }
    }
    jdbc.batchUpdate(
        """
        INSERT INTO product (product_id,name,description,price_minor,currency,stock_quantity,
          available,publication_state,publication_version)
        VALUES (?,?,'Cart line fixture',500,'USD',200,TRUE,'PUBLISHED',1)
        """,
        products);
    jdbc.update("INSERT INTO shopping_cart (user_subject,cart_version) VALUES (?,99)", owner);
    jdbc.batchUpdate(
        "INSERT INTO shopping_cart_item (user_subject,product_id,quantity) VALUES (?,?,1)", items);
    var outcomes =
        concurrently(
            () -> addCategory("hundred-a", owner + "-line-99"),
            () -> addCategory("hundred-b", owner + "-line-100"));
    assertThat(outcomes).containsExactlyInAnyOrder("OK", "CART_LIMIT");
    assertThat(count("shopping_cart_item")).isEqualTo(100);
    assertThat(count("shopping_cart_command")).isEqualTo(1);
    assertThat(service.get(owner).version()).isEqualTo(100);
  }

  @Test
  void accountAndCommandIdentitiesRemainExactIncludingCaseAndTrailingSpace() {
    assertThat(service.get(owner).items()).isEmpty();
    assertThat(service.command(owner, "unknown")).isEmpty();
    assertThat(count("shopping_cart")).isZero();
    for (String key : List.of("Key", "key", "key ", "买家命令")) {
      service.add(owner, key, sku, 1);
    }
    String upper = owner.toUpperCase(Locale.ROOT);
    service.add(upper, "Key", sku, 2);
    service.add(owner + " ", "Key", sku, 3);
    assertThat(quantity()).isEqualTo(4);
    assertThat(service.get(upper).items().getFirst().quantity()).isEqualTo(2);
    assertThat(service.get(owner + " ").items().getFirst().quantity()).isEqualTo(3);
    assertThat(count("shopping_cart_command")).isEqualTo(4);
    assertThat(service.command(upper, "key")).isEmpty();
  }

  @Test
  void laterPublicationChangesPreserveLinesAndInvalidateMixedCurrencyQuote() {
    String other = owner + "-other";
    String foreign = owner + "-foreign";
    product(other, "USD");
    product(foreign, "CNY");
    service.add(owner, "first", sku, 2);
    service.add(owner, "second", other, 1);
    assertThatThrownBy(() -> service.add(owner, "foreign", foreign, 1))
        .isInstanceOfSatisfying(
            CartException.class,
            exception -> assertThat(exception.category()).isEqualTo("CURRENCY_CONFLICT"));
    jdbc.update(
        "UPDATE product SET currency='CNY',publication_version=2 WHERE product_id=?", other);
    var mixed = service.get(owner);
    assertThat(mixed.items()).hasSize(2);
    assertThat(mixed.subtotalMinor()).isNull();
    assertThat(mixed.currency()).isNull();
    assertThat(mixed.checkoutReady()).isFalse();
    jdbc.update(
        "UPDATE product SET currency='USD',publication_state='UNPUBLISHED',stock_quantity=0,"
            + "publication_version=3 WHERE product_id=?",
        other);
    var unavailable = service.get(owner);
    assertThat(unavailable.items()).hasSize(2);
    assertThat(unavailable.checkoutReady()).isFalse();
    assertThat(
            unavailable.items().stream()
                .filter(item -> item.productId().equals(other))
                .findFirst()
                .orElseThrow()
                .orderable())
        .isFalse();
    jdbc.update(
        "UPDATE product SET publication_state='PUBLISHED',stock_quantity=200,price_minor=0,"
            + "publication_version=4 WHERE product_id=?",
        other);
    var zeroPrice = service.get(owner);
    assertThat(zeroPrice.items()).hasSize(2);
    assertThat(zeroPrice.checkoutReady()).isFalse();
    var unpaidLine =
        zeroPrice.items().stream()
            .filter(item -> item.productId().equals(other))
            .findFirst()
            .orElseThrow();
    assertThat(unpaidLine.unitPriceMinor()).isZero();
    assertThat(unpaidLine.orderable()).isFalse();
    assertThatThrownBy(() -> service.add(owner, "zero-price", other, 1))
        .isInstanceOfSatisfying(
            CartException.class,
            exception -> assertThat(exception.category()).isEqualTo("NOT_ORDERABLE"));
    assertThat(
            jdbc.queryForObject(
                "SELECT quantity FROM shopping_cart_item WHERE user_subject=? AND product_id=?",
                Integer.class,
                owner,
                other))
        .isEqualTo(1);
    assertThat(count("shopping_cart_command")).isEqualTo(2);
  }

  @Test
  void variantPresentationComesFromPersistedMetadataWhilePriceRemainsProductTruth()
      throws Exception {
    String family = owner + "-family";
    try (var connection =
        DriverManager.getConnection(
            required("CATALOG_MYSQL_URL"),
            "bootstrap_admin",
            required("MYSQL_BOOTSTRAP_PASSWORD"))) {
      var fixture = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      fixture.execute("SET ROLE 'bootstrap_grant_role'");
      try {
        fixture.update(
            """
            INSERT INTO retail_product_family (family_id,name,description,content,options)
            VALUES (?,'Shirts','Cart family',
              '{"imageUrl":"https://example.test/family.png"}',
              '[{"name":"size","values":["M"]}]')
            """,
            family);
        fixture.update(
            """
            INSERT INTO retail_product_metadata (product_id,family_id,content,option_values)
            VALUES (?,?,'{}','{"size":"M"}')
            """,
            sku,
            family);
      } finally {
        fixture.execute("SET ROLE NONE");
      }
    }
    var cart = service.add(owner, "variant", sku, 2).cart();
    var item = cart.items().getFirst();
    assertThat(item.imageUrl()).isEqualTo("https://example.test/family.png");
    assertThat(item.optionValues()).isEqualTo(Map.of("size", "M"));
    assertThat(item.familyId()).isEqualTo(family);
    assertThat(item.unitPriceMinor()).isEqualTo(500);
    assertThat(item.lineTotalMinor()).isEqualTo(1000);
    assertThatThrownBy(() -> service.add(owner, "family", family, 1))
        .isInstanceOfSatisfying(
            CartException.class,
            exception -> assertThat(exception.category()).isEqualTo("NOT_ORDERABLE"));
    assertThat(count("shopping_cart_item")).isEqualTo(1);
  }

  private void product(String id, String currency) {
    jdbc.update(
        """
        INSERT INTO product (product_id,name,description,price_minor,currency,stock_quantity,
          available,publication_state,publication_version)
        VALUES (?,?,'Cart fixture',500,?,200,TRUE,'PUBLISHED',1)
        """,
        id,
        id,
        currency);
  }

  private String addCategory(String key, String productId) {
    try {
      service.add(owner, key, productId, 1);
      return "OK";
    } catch (CartException exception) {
      return exception.category();
    }
  }

  private long count(String table) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE user_subject=?", Long.class, owner);
  }

  private int quantity() {
    return jdbc.queryForObject(
        "SELECT quantity FROM shopping_cart_item WHERE user_subject=? AND product_id=?",
        Integer.class,
        owner,
        sku);
  }

  private static <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var futures =
          List.of(first, second).stream()
              .map(
                  work ->
                      workers.submit(
                          () -> {
                            ready.countDown();
                            if (!start.await(10, TimeUnit.SECONDS)) {
                              throw new IllegalStateException(
                                  "Concurrent cart requests did not start");
                            }
                            return work.call();
                          }))
              .toList();
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(
          futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }
}
