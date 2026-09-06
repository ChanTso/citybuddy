package io.citybuddy.commerce.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.merchant.MerchantChangeModels.Command;
import io.citybuddy.commerce.merchant.MerchantModels.Context;
import io.citybuddy.commerce.merchant.MerchantModels.PrepareCommand;
import io.citybuddy.commerce.merchant.MerchantModels.PriceInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// Each class owns a database pool; release it before later integration applications start.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MerchantChangeIntegrationTest {
  @DynamicPropertySource
  static void integrationProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
    registry.add("spring.data.redis.url", () -> required("CATALOG_REDIS_URL"));
    registry.add("citybuddy.catalog.enabled", () -> "true");
    registry.add("citybuddy.catalog.issuer", () -> "https://identity.citybuddy.test");
    registry.add("citybuddy.catalog.user-audience", () -> "citybuddy-web");
    registry.add("citybuddy.catalog.jwks-url", () -> required("IDENTITY_JWKS_URL"));
    registry.add("citybuddy.catalog.required-permission", () -> "catalog:read");
    registry.add("citybuddy.catalog.worker-initial-delay-ms", () -> "3600000");
    registry.add("citybuddy.catalog.worker-delay-ms", () -> "3600000");
    registry.add("citybuddy.catalog.rocketmq-endpoints", () -> required("ROCKETMQ_ENDPOINTS"));
    registry.add("citybuddy.catalog.rocketmq-topic", () -> required("ROCKETMQ_TOPIC"));
    registry.add(
        "citybuddy.catalog.rocketmq-consumer-group", () -> required("ROCKETMQ_CONSUMER_GROUP"));
    registry.add("citybuddy.obo.enabled", () -> "true");
    registry.add("citybuddy.obo.issuer", () -> "https://identity.citybuddy.test");
    registry.add("citybuddy.obo.jwks-url", () -> required("IDENTITY_JWKS_URL"));
    registry.add("citybuddy.merchant.enabled", () -> "true");
  }

  @Autowired private MerchantChangeService changes;
  @Autowired private MerchantService prices;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @MockitoSpyBean private MerchantChangeRepository repository;
  private Context context;
  private final List<String> products = new ArrayList<>();

  @BeforeEach
  void seed() {
    products.clear();
    String prefix = "change-it-" + UUID.randomUUID().toString().substring(0, 8);
    context = new Context(prefix + "-operator", prefix + "-session");
    for (int i = 0; i < 3; i++) {
      seedProduct(prefix + "-" + i);
    }
  }

  @AfterEach
  void retireFixture() {
    reset(repository);
    for (String product : products) {
      jdbc.update("UPDATE product SET publication_state='UNPUBLISHED' WHERE product_id=?", product);
    }
  }

  @Test
  void proposalAndRepeatedApprovalUseOneDurableReceiptAndCurrentStock() {
    var proposal = changes.prepare(context, "restock", inventory(0, 10));
    assertThat(stock(0)).isEqualTo(7);
    assertThat(proposal.items().get(0).path("before").asLong()).isEqualTo(7);
    jdbc.update("UPDATE product SET stock_quantity=5 WHERE product_id=?", products.getFirst());
    var applied = changes.apply(context.operatorSubject(), proposal.changeId());
    assertThat(applied.state()).isEqualTo("APPLIED");
    assertThat(stock(0)).isEqualTo(15);
    assertThat(eventCount(0)).isEqualTo(1);
    assertThat(changes.apply(context.operatorSubject(), proposal.changeId())).isEqualTo(applied);
    assertThat(changes.get(context, proposal.changeId())).isEqualTo(applied);
    assertThat(changes.prepare(context, "restock", inventory(0, 10))).isEqualTo(applied);
    assertThat(stock(0)).isEqualTo(15);
    assertThat(eventCount(0)).isEqualTo(1);
  }

  @Test
  void staleBatchCommitsOnlyARejectionAndReplaysIt() {
    Command command =
        command(
            "INVENTORY_ACTION",
            Map.of(
                "items",
                List.of(
                    Map.of("listingId", products.get(0), "action", "restock", "quantity", 10),
                    Map.of("listingId", products.get(1), "action", "pause"))));
    var proposal = changes.prepare(context, "stale", command);
    jdbc.update("UPDATE product SET publication_version=2 WHERE product_id=?", products.get(1));
    var rejected = changes.apply(context.operatorSubject(), proposal.changeId());
    assertThat(rejected.state()).isEqualTo("REJECTED");
    assertThat(rejected.result().path("reason").asText()).isEqualTo("VERSION_CONFLICT");
    assertThat(stock(0)).isEqualTo(7);
    assertThat(eventCount(0)).isZero();
    assertThat(eventCount(1)).isZero();
    assertThat(changes.apply(context.operatorSubject(), proposal.changeId())).isEqualTo(rejected);
    assertThat(changes.get(context, proposal.changeId())).isEqualTo(rejected);
  }

  @Test
  void receiptFailureRollsBackProductAndOutboxTogether() {
    var proposal = changes.prepare(context, "failure", inventory(0, 10));
    long generation =
        jdbc.queryForObject(
            "SELECT publication_generation FROM catalog_metadata WHERE singleton_id=1", Long.class);
    doThrow(new IllegalStateException("injected persistence failure"))
        .when(repository)
        .resolve(eq(proposal.changeId()), eq("APPLIED"), any(), any());
    assertThatThrownBy(() -> changes.apply(context.operatorSubject(), proposal.changeId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("injected");
    assertThat(stock(0)).isEqualTo(7);
    assertThat(eventCount(0)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT publication_version FROM product WHERE product_id=?",
                Long.class,
                products.getFirst()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT publication_generation FROM catalog_metadata WHERE singleton_id=1",
                Long.class))
        .isEqualTo(generation);
    assertThat(changes.get(context, proposal.changeId()).state()).isEqualTo("PREPARED");
    reset(repository);
    assertThat(changes.apply(context.operatorSubject(), proposal.changeId()).state())
        .isEqualTo("APPLIED");
    assertThat(stock(0)).isEqualTo(17);
  }

  @Test
  void duplicateConcurrentApprovalsPublishOneChange() throws Exception {
    var proposal = changes.prepare(context, "concurrent", inventory(0, 10));
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return changes.apply(context.operatorSubject(), proposal.changeId());
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return changes.apply(context.operatorSubject(), proposal.changeId());
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
    } finally {
      start.countDown();
    }
    assertThat(stock(0)).isEqualTo(17);
    assertThat(eventCount(0)).isEqualTo(1);
  }

  @Test
  void kindsShareIdempotencyNamespaceAndLegacyPriceEndpointsRemainPriceOnly() {
    var proposal = changes.prepare(context, "same", inventory(0, 10));
    var price = new PrepareCommand("CNY", List.of(new PriceInput(products.getFirst(), 1100)));
    assertThatThrownBy(() -> prices.prepare(context, "same", price))
        .isInstanceOf(MerchantException.class)
        .extracting("category")
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThatThrownBy(() -> prices.get(context, proposal.changeId()))
        .isInstanceOf(MerchantException.class);
    assertThatThrownBy(() -> prices.apply(context.operatorSubject(), proposal.changeId()))
        .isInstanceOf(MerchantException.class);
    var old = prices.prepare(context, "old", price);
    assertThatThrownBy(() -> changes.prepare(context, "old", inventory(0, 10)))
        .isInstanceOf(MerchantException.class)
        .extracting("category")
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(changes.get(context, old.draftId()).kind()).isEqualTo("PRICE_UPDATE");
    assertThat(changes.apply(context.operatorSubject(), old.draftId()).state())
        .isEqualTo("APPLIED");
    assertThat(stock(0)).isEqualTo(7);
    assertThat(changes.list(context, "PREPARED", 20, 0)).containsExactly(proposal);
  }

  @Test
  void ownershipSessionAndCancellationRemainEffectiveForEveryKind() {
    var proposal = changes.prepare(context, "cancel", inventory(0, 10));
    for (Context stranger :
        List.of(
            new Context(context.operatorSubject() + "x", context.sessionId()),
            new Context(context.operatorSubject(), context.sessionId() + "x"))) {
      assertThat(changes.list(stranger, null, 20, 0)).isEmpty();
      assertThatThrownBy(() -> changes.get(stranger, proposal.changeId()))
          .isInstanceOf(MerchantException.class);
      assertThatThrownBy(() -> changes.cancel(stranger, proposal.changeId()))
          .isInstanceOf(MerchantException.class);
    }
    assertThatThrownBy(() -> changes.apply(context.operatorSubject() + "x", proposal.changeId()))
        .isInstanceOf(MerchantException.class);
    var cancelled = changes.cancel(context, proposal.changeId());
    assertThat(cancelled.state()).isEqualTo("CANCELLED");
    assertThat(changes.apply(context.operatorSubject(), proposal.changeId())).isEqualTo(cancelled);
    assertThat(changes.cancel(context, proposal.changeId())).isEqualTo(cancelled);
    assertThat(stock(0)).isEqualTo(7);
    assertThat(eventCount(0)).isZero();
  }

  @Test
  void reorderedJsonFieldsReplayTheSameListingIntent() throws Exception {
    Command original =
        new Command(
            "LISTING_UPDATE",
            mapper.readTree(
                "{\"listingId\":\""
                    + products.getFirst()
                    + "\",\"fields\":{\"title\":\"New title\",\"material\":\"Cotton\"}}"));
    Command reordered =
        new Command(
            "LISTING_UPDATE",
            mapper.readTree(
                "{\"fields\":{\"material\":\"Cotton\",\"title\":\"New title\"},\"listingId\":\""
                    + products.getFirst()
                    + "\"}"));
    var proposal = changes.prepare(context, "canonical", original);
    assertThat(changes.prepare(context, "canonical", reordered)).isEqualTo(proposal);
    var applied = changes.apply(context.operatorSubject(), proposal.changeId());
    assertThat(applied.state()).isEqualTo("APPLIED");
    assertThat(
            jdbc.queryForObject(
                "SELECT name FROM product WHERE product_id=?", String.class, products.getFirst()))
        .isEqualTo("New title");
    assertThat(changes.prepare(context, "canonical", reordered)).isEqualTo(applied);
    assertThatThrownBy(
            () ->
                changes.prepare(
                    context,
                    "canonical",
                    command(
                        "LISTING_UPDATE",
                        Map.of(
                            "listingId", products.getFirst(), "fields", Map.of("title", "Other")))))
        .isInstanceOf(MerchantException.class)
        .extracting("category")
        .isEqualTo("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void unifiedPriceOperationSupportsTwentyFiveProductsWithoutChangingLegacyReceipts() {
    for (int i = 3; i < 25; i++) {
      seedProduct(products.getFirst() + "-extra-" + i);
    }
    var items =
        products.stream().map(id -> Map.of("productId", id, "newPriceMinor", 1100)).toList();
    var proposal =
        changes.prepare(
            context,
            "large-price",
            command("PRICE_UPDATE", Map.of("currency", "CNY", "items", items)));
    assertThat(proposal.kind()).isEqualTo("PRICE_UPDATE");
    assertThat(proposal.items()).hasSize(25);
    var applied = changes.apply(context.operatorSubject(), proposal.changeId());
    assertThat(applied.state()).isEqualTo("APPLIED");
    assertThat(prices.get(context, proposal.changeId()).state()).isEqualTo("APPLIED");
    assertThat(
            changes.prepare(
                context,
                "large-price",
                command("PRICE_UPDATE", Map.of("currency", "CNY", "items", items))))
        .isEqualTo(applied);
    for (int i = 0; i < 25; i++) {
      assertThat(eventCount(i)).isEqualTo(1);
      assertThat(stock(i)).isEqualTo(7);
      assertThat(
              jdbc.queryForObject(
                  "SELECT price_minor FROM product WHERE product_id=?",
                  Long.class,
                  products.get(i)))
          .isEqualTo(1100);
    }
  }

  private Command inventory(int index, int quantity) {
    return command(
        "INVENTORY_ACTION",
        Map.of(
            "items",
            List.of(
                Map.of(
                    "listingId", products.get(index), "action", "restock", "quantity", quantity))));
  }

  private Command command(String kind, Object payload) {
    return new Command(kind, mapper.valueToTree(payload));
  }

  private long stock(int index) {
    return jdbc.queryForObject(
        "SELECT stock_quantity FROM product WHERE product_id=?", Long.class, products.get(index));
  }

  private long eventCount(int index) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_id=? AND event_type='PRODUCT_PUBLICATION_CHANGED'",
        Long.class,
        products.get(index));
  }

  private void seedProduct(String id) {
    jdbc.update(
        "INSERT INTO product(product_id,name,description,price_minor,currency,stock_quantity,available,publication_state,publication_version) VALUES (?,?,'Retail change test',1000,'CNY',7,TRUE,'PUBLISHED',1)",
        id,
        id);
    products.add(id);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing integration environment: " + name);
    }
    return value;
  }
}
