package io.citybuddy.commerce.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.cart.CartConfiguration;
import io.citybuddy.commerce.cart.CartService;
import io.citybuddy.commerce.catalog.ProductCache;
import io.citybuddy.commerce.catalog.ProductPublicationService;
import io.citybuddy.commerce.catalog.ProductRepository;
import io.citybuddy.commerce.checkout.CheckoutConfiguration;
import io.citybuddy.commerce.checkout.CheckoutException;
import io.citybuddy.commerce.checkout.CheckoutModels;
import io.citybuddy.commerce.merchant.MerchantChangeModels.Command;
import io.citybuddy.commerce.merchant.MerchantModels.Context;
import io.citybuddy.commerce.order.BatchOrderService;
import io.citybuddy.commerce.order.OrderConfiguration;
import io.citybuddy.commerce.payment.MockPaymentCallbackRequest;
import io.citybuddy.commerce.payment.MockPaymentRepository;
import io.citybuddy.commerce.payment.MockPaymentRequest;
import io.citybuddy.commerce.payment.MockPaymentService;
import io.citybuddy.commerce.refund.RefundConfiguration;
import io.citybuddy.commerce.refund.RefundRequest;
import io.citybuddy.commerce.refund.RefundService;
import io.citybuddy.commerce.shopping.ShoppingOrderConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(
    classes = MerchantMarketingIntegrationTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MerchantMarketingIntegrationTest {
  private static final Instant NOW =
      Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({
    MerchantConfiguration.class,
    MerchantChangeConfiguration.class,
    OrderConfiguration.class,
    CartConfiguration.class,
    CheckoutConfiguration.class,
    ShoppingOrderConfiguration.class,
    RefundConfiguration.class
  })
  static class Application {
    @Bean
    MutableClock catalogClock() {
      return new MutableClock();
    }

    @Bean
    ProductRepository products(JdbcTemplate jdbc, ObjectMapper mapper) {
      return new ProductRepository(jdbc, mapper);
    }

    @Bean
    ProductPublicationService publication(ProductRepository products) {
      return new ProductPublicationService(products, mock(ProductCache.class));
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("citybuddy.merchant.enabled", () -> "true");
    registry.add("citybuddy.orders.enabled", () -> "true");
    registry.add("citybuddy.refund.enabled", () -> "true");
  }

  @Autowired private MerchantChangeService changes;
  @Autowired private MerchantMarketingRepository marketing;
  @Autowired private ProductPublicationService publication;
  @Autowired private CartService carts;
  @Autowired private BatchOrderService checkouts;
  @Autowired private RefundService refunds;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private MutableClock clock;
  @MockitoSpyBean private MerchantChangeRepository ledger;
  private JdbcTemplate fixture;
  private Context context;
  private String prefix;
  private final List<String> products = new ArrayList<>();

  @BeforeEach
  void setup() {
    clock.now = NOW;
    prefix = "marketing-" + UUID.randomUUID().toString().substring(0, 8);
    context = new Context(prefix + "-operator", prefix + "-session");
    fixture =
        new JdbcTemplate(
            new DriverManagerDataSource(
                required("CATALOG_MYSQL_URL"), "root", required("MYSQL_BOOTSTRAP_PASSWORD")));
  }

  @AfterEach
  void cleanup() {
    reset(ledger);
    for (String product : products) {
      jdbc.update("UPDATE product SET publication_state='UNPUBLISHED' WHERE product_id=?", product);
    }
  }

  @Test
  void promotionChangesTheSinglePriceAuthorityThroughCartCheckoutPaymentAndRefund() {
    String sku = product("a", 10000);
    String oldOwner = prefix + "-old";
    carts.add(oldOwner, "old-cart", sku, 1);
    var oldOrder =
        checkouts
            .create(oldOwner, "old-buy", quote(oldOwner), "before-promotion")
            .orders()
            .getFirst();
    String buyer = prefix + "-buyer";
    carts.add(buyer, "cart", sku, 1);
    CheckoutModels.Command staleQuote = quote(buyer);
    var prepared =
        changes.prepare(
            context,
            "promotion",
            promotion(List.of(sku), 10, NOW.minusSeconds(1), NOW.plusSeconds(3600)));
    assertThat(price(sku)).isEqualTo(10000);
    var applied = changes.apply(context.operatorSubject(), prepared.changeId());
    String promotionId = applied.result().path("operation").path("promotionId").asText();
    assertThat(price(sku)).isEqualTo(9000);
    assertThat(carts.get(buyer).subtotalMinor()).isEqualTo(9000);
    assertThatThrownBy(() -> checkouts.create(buyer, "stale", staleQuote, "old-quote"))
        .isInstanceOfSatisfying(
            CheckoutException.class,
            error -> assertThat(error.category()).isEqualTo("stale_quote"));
    var checkout = checkouts.create(buyer, "buy", quote(buyer), "promotion-buy");
    assertThat(checkout.totalMinor()).isEqualTo(9000);
    String orderId = checkout.orders().getFirst().orderId();
    var payments =
        new MockPaymentService(
            new MockPaymentRepository(jdbc), new TransactionTemplate(transactionManager), clock);
    var attempt = payments.start(buyer, orderId, "pay", new MockPaymentRequest(9000L, "CNY", null));
    payments.callback(
        prefix + "-callback",
        new MockPaymentCallbackRequest(
            UUID.randomUUID().toString(),
            attempt.callbackCorrelationId(),
            orderId,
            9000L,
            "CNY",
            "SUCCEEDED"));
    var transactions = new TransactionTemplate(transactionManager);
    var target =
        transactions.execute(
            status ->
                refunds.prepareActionInCurrentTransaction(
                    buyer, orderId, new RefundRequest(9000L, "CNY", null), null, true));
    assertThat(target.attempt().amountMinor()).isEqualTo(9000);
    var refund = refunds.request(buyer, orderId, "refund", new RefundRequest(9000L, "CNY", null));
    assertThat(refund.requestedAmountMinor()).isEqualTo(9000);
    assertThat(refund.eligibleAmountMinor()).isEqualTo(9000);
    assertThat(refund.state()).isEqualTo("REQUESTED");
    assertThat(changes.apply(context.operatorSubject(), prepared.changeId())).isEqualTo(applied);
    assertThat(
            changes.prepare(
                context,
                "promotion",
                promotion(List.of(sku), 10, NOW.minusSeconds(1), NOW.plusSeconds(3600))))
        .isEqualTo(applied);
    assertThat(marketing.promotion(promotionId).orElseThrow().targets())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.promotionPriceMinor()).isEqualTo(9000);
              assertThat(item.overridden()).isFalse();
              assertThat(item.eventId()).isNotBlank();
            });
    publication.changePrices(List.of(new ProductRepository.PriceChange(sku, 2, 9500)), "CNY");
    assertThat(marketing.promotion(promotionId).orElseThrow().targets().getFirst().overridden())
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT total_price_minor FROM standard_order WHERE order_id=?",
                Long.class,
                orderId))
        .isEqualTo(9000);
    assertThat(
            jdbc.queryForObject(
                "SELECT total_price_minor FROM standard_order WHERE order_id=?",
                Long.class,
                oldOrder.orderId()))
        .isEqualTo(10000);
  }

  @Test
  void promotionReadDistinguishesContentPublicationFromPriceOrCurrencyReplacement() {
    String sku = product("currency", 10000);
    var proposal =
        changes.prepare(
            context,
            "currency",
            promotion(List.of(sku), 10, NOW.minusSeconds(1), NOW.plusSeconds(3600)));
    var applied = changes.apply(context.operatorSubject(), proposal.changeId());
    String promotionId = applied.result().path("operation").path("promotionId").asText();
    publication.publish(
        new ProductRepository.ProductDraft(
            sku, "Updated title", "Updated description", 9000, "CNY", 50, true, true),
        UUID.randomUUID());
    var contentUpdate = marketing.promotion(promotionId).orElseThrow().targets().getFirst();
    assertThat(contentUpdate.currentVersion()).isEqualTo(3);
    assertThat(contentUpdate.currentCurrency()).isEqualTo("CNY");
    assertThat(contentUpdate.currentPriceMinor()).isEqualTo(9000);
    assertThat(contentUpdate.overridden()).isFalse();
    publication.publish(
        new ProductRepository.ProductDraft(
            sku, "Updated title", "Updated description", 9000, "USD", 50, true, true),
        UUID.randomUUID());
    var currencyUpdate = marketing.promotion(promotionId).orElseThrow();
    assertThat(currencyUpdate.currency()).isEqualTo("CNY");
    assertThat(currencyUpdate.targets())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.currentPriceMinor()).isEqualTo(item.promotionPriceMinor());
              assertThat(item.currentCurrency()).isEqualTo("USD");
              assertThat(item.currentVersion()).isEqualTo(4);
              assertThat(item.overridden()).isTrue();
            });
  }

  @Test
  void operatingWindowDoesNotApplyEarlyOrRestorePriceAfterItsEnd() {
    String sku = product("window", 10000);
    var prepared =
        changes.prepare(
            context,
            "future",
            promotion(List.of(sku), 10, NOW.plusSeconds(10), NOW.plusSeconds(20)));
    assertThatThrownBy(() -> changes.apply(context.operatorSubject(), prepared.changeId()))
        .isInstanceOfSatisfying(
            MerchantException.class,
            error -> assertThat(error.category()).isEqualTo("promotion_not_started"));
    assertThat(changes.get(context, prepared.changeId()).state()).isEqualTo("PREPARED");
    assertThat(price(sku)).isEqualTo(10000);
    clock.now = NOW.plusSeconds(10);
    var applied = changes.apply(context.operatorSubject(), prepared.changeId());
    String promotionId = applied.result().path("operation").path("promotionId").asText();
    clock.now = NOW.plusSeconds(20);
    assertThat(marketing.promotion(promotionId).orElseThrow().state()).isEqualTo("ended");
    assertThat(price(sku)).isEqualTo(9000);
    assertThat(changes.apply(context.operatorSubject(), prepared.changeId())).isEqualTo(applied);
    String expiredSku = product("expired", 10000);
    var expired =
        changes.prepare(
            context, "expired", promotion(List.of(expiredSku), 10, NOW, NOW.plusSeconds(20)));
    var rejected = changes.apply(context.operatorSubject(), expired.changeId());
    assertThat(rejected.state()).isEqualTo("REJECTED");
    assertThat(rejected.result().path("reason").asText()).isEqualTo("PROMOTION_EXPIRED");
    assertThat(price(expiredSku)).isEqualTo(10000);
    assertThat(promotionCount(expired.changeId())).isZero();
  }

  @Test
  void stageUsesIntegerHalfUpAndRejectsInvalidDiscountsOrUnchangedMinorPrices() {
    String sku = product("round", 101);
    var proposal =
        changes.prepare(
            context, "half", promotion(List.of(sku), 50, NOW.minusSeconds(1), NOW.plusSeconds(60)));
    assertThat(proposal.items().get(0).path("after").asLong()).isEqualTo(51);
    changes.apply(context.operatorSubject(), proposal.changeId());
    assertThat(price(sku)).isEqualTo(51);
    for (double discount : new double[] {0, -10, 50.01, 0.001}) {
      assertThatThrownBy(
              () ->
                  changes.prepare(
                      context,
                      "bad-" + discount,
                      promotion(List.of(sku), discount, NOW.minusSeconds(1), NOW.plusSeconds(60))))
          .isInstanceOf(MerchantException.class);
    }
    String one = product("one", 1);
    assertThatThrownBy(
            () ->
                changes.prepare(
                    context,
                    "tiny",
                    promotion(List.of(one), 10, NOW.minusSeconds(1), NOW.plusSeconds(60))))
        .isInstanceOf(MerchantException.class);
  }

  @Test
  void changedFamilyMembershipRejectsEveryTargetBeforeAnyPriceWrite() {
    String first = product("family-a", 10000);
    String second = product("family-b", 20000);
    String family = family(List.of(first, second));
    var proposal =
        changes.prepare(
            context,
            "family",
            promotion(List.of(family), 10, NOW.minusSeconds(1), NOW.plusSeconds(60)));
    String third = product("family-c", 30000);
    fixture.update(
        "INSERT INTO retail_product_metadata(product_id,family_id,content,option_values,metadata_version) VALUES (?,?,'{}','{}',1)",
        third,
        family);
    var rejected = changes.apply(context.operatorSubject(), proposal.changeId());
    assertThat(rejected.state()).isEqualTo("REJECTED");
    assertThat(rejected.result().path("reason").asText()).isEqualTo("FAMILY_VERSION_CONFLICT");
    assertThat(price(first)).isEqualTo(10000);
    assertThat(price(second)).isEqualTo(20000);
    assertThat(events(first)).isZero();
    assertThat(promotionCount(proposal.changeId())).isZero();
    assertThatThrownBy(
            () ->
                changes.prepare(
                    context,
                    "duplicates",
                    promotion(
                        List.of(family, first), 10, NOW.minusSeconds(1), NOW.plusSeconds(60))))
        .isInstanceOf(MerchantException.class);
  }

  @Test
  void oneStaleSkuRejectsTheWholeFamilyAndOversizedExpansionIsNotSplit() {
    List<String> members = new ArrayList<>();
    for (int index = 0; index < 25; index++) {
      members.add(product("bounded-" + index, 10000));
    }
    String family = family(members);
    var proposal =
        changes.prepare(
            context,
            "bounded",
            promotion(List.of(family), 10, NOW.minusSeconds(1), NOW.plusSeconds(60)));
    assertThat(proposal.items()).hasSize(25);
    jdbc.update("UPDATE product SET publication_version=2 WHERE product_id=?", members.getLast());
    var rejected = changes.apply(context.operatorSubject(), proposal.changeId());
    assertThat(rejected.state()).isEqualTo("REJECTED");
    assertThat(rejected.result().path("reason").asText()).isEqualTo("VERSION_CONFLICT");
    for (String member : members) {
      assertThat(price(member)).isEqualTo(10000);
      assertThat(events(member)).isZero();
    }
    assertThat(promotionCount(proposal.changeId())).isZero();
    String extra = product("bounded-extra", 10000);
    fixture.update(
        "INSERT INTO retail_product_metadata(product_id,family_id,content,option_values,metadata_version) VALUES (?,?,'{}','{}',1)",
        extra,
        family);
    assertThatThrownBy(
            () ->
                changes.prepare(
                    context,
                    "too-many",
                    promotion(List.of(family), 10, NOW.minusSeconds(1), NOW.plusSeconds(60))))
        .isInstanceOf(MerchantException.class);
  }

  @Test
  void calendarWindowUsesShanghaiDaysAndIncludesTheEndDate() {
    String sku = product("calendar", 10000);
    clock.now = Instant.parse("2026-09-06T16:00:00Z");
    var proposal =
        changes.prepare(
            context,
            "calendar",
            command(
                "PROMOTION",
                Map.of(
                    "name",
                    "Calendar",
                    "listingIds",
                    List.of(sku),
                    "discountPct",
                    10,
                    "starts",
                    "2026-09-07",
                    "ends",
                    "2026-09-07")));
    var applied = changes.apply(context.operatorSubject(), proposal.changeId());
    var promotion =
        marketing
            .promotion(applied.result().path("operation").path("promotionId").asText())
            .orElseThrow();
    assertThat(promotion.startsAt()).isEqualTo(Instant.parse("2026-09-06T16:00:00Z"));
    assertThat(promotion.endsAt()).isEqualTo(Instant.parse("2026-09-07T16:00:00Z"));
    assertThat(promotion.state()).isEqualTo("active");
  }

  @Test
  void receiptFailureRollsBackPricesTargetsActivityAndOutboxThenRetryCommitsOnce() {
    String sku = product("rollback", 10000);
    var proposal =
        changes.prepare(
            context,
            "rollback",
            promotion(List.of(sku), 10, NOW.minusSeconds(1), NOW.plusSeconds(60)));
    doThrow(new IllegalStateException("injected receipt failure"))
        .when(ledger)
        .resolve(eq(proposal.changeId()), eq("APPLIED"), any(), any());
    assertThatThrownBy(() -> changes.apply(context.operatorSubject(), proposal.changeId()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(price(sku)).isEqualTo(10000);
    assertThat(events(sku)).isZero();
    assertThat(promotionCount(proposal.changeId())).isZero();
    assertThat(changes.get(context, proposal.changeId()).state()).isEqualTo("PREPARED");
    reset(ledger);
    var applied = changes.apply(context.operatorSubject(), proposal.changeId());
    assertThat(applied.state()).isEqualTo("APPLIED");
    assertThat(promotionCount(proposal.changeId())).isEqualTo(1);
    assertThat(events(sku)).isEqualTo(1);
  }

  @Test
  void concurrentPromotionApprovalReturnsOneReceipt() throws Exception {
    String sku = product("parallel", 10000);
    var proposal =
        changes.prepare(
            context,
            "concurrent",
            promotion(List.of(sku), 10, NOW.minusSeconds(1), NOW.plusSeconds(60)));
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return changes.apply(context.operatorSubject(), proposal.changeId());
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return changes.apply(context.operatorSubject(), proposal.changeId());
              });
      start.countDown();
      assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
    } finally {
      start.countDown();
    }
    assertThat(events(sku)).isEqualTo(1);
    assertThat(promotionCount(proposal.changeId())).isEqualTo(1);
  }

  @Test
  void campaignPlanPersistsActualFieldsPreservesObservationsAndRejectsStaleApprovals() {
    var create =
        changes.prepare(
            context,
            "new-campaign",
            command(
                "CAMPAIGN",
                Map.of(
                    "name",
                    prefix,
                    "objective",
                    "Retain customers",
                    "audience",
                    "Existing buyers",
                    "budgetMinor",
                    50000,
                    "copyText",
                    "New collection")));
    var applied = changes.apply(context.operatorSubject(), create.changeId());
    String id = applied.result().path("operation").path("campaignId").asText();
    var created = marketing.campaign(id).orElseThrow();
    assertThat(created.name()).isEqualTo(prefix);
    assertThat(created.audience()).isEqualTo("Existing buyers");
    assertThat(created.copyText()).isEqualTo("New collection");
    assertThat(created.spendMinor()).isNull();
    assertThat(created.revenueMinor()).isNull();
    assertThat(created.observedAt()).isNull();
    assertThat(created.state()).isEqualTo("draft");
    assertThat(changes.apply(context.operatorSubject(), create.changeId())).isEqualTo(applied);
    fixture.update(
        """
        UPDATE retail_campaign SET spend_minor=700,revenue_minor=1700,observation_source_kind='fixture',
        observation_source_ref='campaign-seed',observed_at='2026-09-07 00:00:00',
        observation_start='2026-08-01 00:00:00',observation_end='2026-09-01 00:00:00',fixture_version='v1'
        WHERE campaign_id=?
        """,
        id);
    var prior = marketing.campaign(id).orElseThrow();
    var update =
        changes.prepare(
            context,
            "update",
            command(
                "CAMPAIGN",
                Map.of(
                    "campaignId",
                    id,
                    "name",
                    prefix + " revised",
                    "audience",
                    "Returning buyers",
                    "budgetMinor",
                    60000,
                    "copyText",
                    "Updated copy")));
    var stale =
        changes.prepare(
            context,
            "stale",
            command("CAMPAIGN", Map.of("campaignId", id, "name", "Stale", "budgetMinor", 65000)));
    changes.apply(context.operatorSubject(), update.changeId());
    var result = marketing.campaign(id).orElseThrow();
    assertThat(result.budgetMinor()).isEqualTo(60000);
    assertThat(result.audience()).isEqualTo("Returning buyers");
    assertThat(result.copyText()).isEqualTo("Updated copy");
    assertThat(result.objective()).isEqualTo(prior.objective());
    assertThat(result.spendMinor()).isEqualTo(prior.spendMinor());
    assertThat(result.revenueMinor()).isEqualTo(prior.revenueMinor());
    assertThat(result.observationStart()).isEqualTo(prior.observationStart());
    assertThat(result.observationEnd()).isEqualTo(prior.observationEnd());
    assertThat(result.fixtureVersion()).isEqualTo("v1");
    assertThat(changes.apply(context.operatorSubject(), stale.changeId()).state())
        .isEqualTo("REJECTED");
    assertThat(marketing.campaign(id).orElseThrow()).isEqualTo(result);
  }

  @Test
  void campaignBoundaryRejectsUnapprovedAttributionAndOverBudget() {
    for (Map<String, Object> payload :
        List.of(
            Map.<String, Object>of("name", "bad", "budgetMinor", 1_000_001),
            Map.<String, Object>of("name", "bad", "budgetMinor", 1.5),
            Map.<String, Object>of("name", "bad", "spendMinor", 100),
            Map.<String, Object>of("name", "bad", "state", "active"))) {
      assertThatThrownBy(() -> changes.prepare(context, "invalid", command("CAMPAIGN", payload)))
          .isInstanceOf(MerchantException.class);
    }
  }

  @Test
  void campaignReceiptFailureRollsBackThePlanAndUnknownAttributionStaysNullOnUpdate() {
    var proposal =
        changes.prepare(
            context,
            "campaign-rollback",
            command("CAMPAIGN", Map.of("name", prefix, "budgetMinor", 0)));
    doThrow(new IllegalStateException("injected campaign receipt failure"))
        .when(ledger)
        .resolve(eq(proposal.changeId()), eq("APPLIED"), any(), any());
    assertThatThrownBy(() -> changes.apply(context.operatorSubject(), proposal.changeId()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM retail_campaign WHERE source_change_id=?",
                Long.class,
                proposal.changeId()))
        .isZero();
    assertThat(changes.get(context, proposal.changeId()).state()).isEqualTo("PREPARED");
    reset(ledger);
    var result = changes.apply(context.operatorSubject(), proposal.changeId());
    String id = result.result().path("operation").path("campaignId").asText();
    var update =
        changes.prepare(
            context,
            "campaign-null",
            command("CAMPAIGN", Map.of("campaignId", id, "name", prefix + " revised")));
    changes.apply(context.operatorSubject(), update.changeId());
    var campaign = marketing.campaign(id).orElseThrow();
    assertThat(campaign.budgetMinor()).isZero();
    assertThat(campaign.spendMinor()).isNull();
    assertThat(campaign.revenueMinor()).isNull();
    assertThat(campaign.observationStart()).isNull();
    assertThat(campaign.observedAt()).isNull();
  }

  private String product(String suffix, long price) {
    String id = prefix + "-" + suffix;
    jdbc.update(
        """
        INSERT INTO product(product_id,name,description,price_minor,currency,stock_quantity,available,publication_state,publication_version)
        VALUES (?,?,'Marketing fixture',?,'CNY',50,true,'PUBLISHED',1)
        """,
        id,
        id,
        price);
    products.add(id);
    return id;
  }

  private String family(List<String> members) {
    String id = prefix + "-family";
    fixture.update(
        "INSERT INTO retail_product_family(family_id,name,description,content,options,metadata_version) VALUES (?,?,'family','{}','[]',1)",
        id,
        id);
    for (String member : members) {
      fixture.update(
          "INSERT INTO retail_product_metadata(product_id,family_id,content,option_values,metadata_version) VALUES (?,?,'{}','{}',1)",
          member,
          id);
    }
    return id;
  }

  private Command promotion(List<String> ids, double discount, Instant start, Instant end) {
    return command(
        "PROMOTION",
        Map.of(
            "name",
            "Promotion",
            "listingIds",
            ids,
            "discountPct",
            discount,
            "starts",
            start.toString(),
            "ends",
            end.toString()));
  }

  private Command command(String kind, Map<String, ?> payload) {
    return new Command(kind, mapper.valueToTree(payload));
  }

  private CheckoutModels.Command quote(String owner) {
    var cart = carts.get(owner);
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

  private long price(String sku) {
    return jdbc.queryForObject(
        "SELECT price_minor FROM product WHERE product_id=?", Long.class, sku);
  }

  private long events(String sku) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_id=? AND event_type='PRODUCT_PUBLICATION_CHANGED'",
        Long.class,
        sku);
  }

  private long promotionCount(String change) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM retail_promotion WHERE source_change_id=?", Long.class, change);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }

  static final class MutableClock extends Clock {
    private volatile Instant now = NOW;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
