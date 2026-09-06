package io.citybuddy.commerce.retail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.citybuddy.commerce.retail.RetailFulfillmentModels.Configuration;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.DeliveryEstimate;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.DeliveryOption;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.EstimateItem;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.EstimateRequest;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.Express;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.Freight;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.Pickup;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.Rules;
import io.citybuddy.commerce.retail.RetailFulfillmentModels.Standard;
import io.citybuddy.commerce.retail.RetailFulfillmentRepository.Sku;
import io.citybuddy.commerce.shopping.ShoppingPreferencesModels.Preferences;
import io.citybuddy.commerce.shopping.ShoppingPreferencesRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetailFulfillmentServiceTest {
  @Test
  void standardAndMemberExpressRequireStrictlyMoreThanTheThreshold() {
    var boundary = quote(4900, 1, true, "2026-09-04T02:00:00Z", null);
    assertThat(option(boundary, "STANDARD").feeMinor()).isEqualTo(599);
    assertThat(option(boundary, "EXPRESS").feeMinor()).isEqualTo(999);

    var member = quote(4901, 1, true, "2026-09-04T02:00:00Z", null);
    assertThat(option(member, "STANDARD").feeMinor()).isZero();
    assertThat(option(member, "EXPRESS").feeMinor()).isZero();
    var ordinary = quote(4901, 1, false, "2026-09-04T02:00:00Z", null);
    assertThat(option(ordinary, "STANDARD").feeMinor()).isZero();
    assertThat(option(ordinary, "EXPRESS").feeMinor()).isEqualTo(999);
  }

  @Test
  void quantityContributesToSubtotalButFreightUsesUnitPriceAndCategory() {
    var quantity = quote(2500, 2, false, "2026-09-04T02:00:00Z", "fitness");
    assertThat(quantity.itemSubtotalMinor()).isEqualTo(5000);
    assertThat(option(quantity, "STANDARD").feeMinor()).isZero();
    assertThat(quantity.items().getFirst().quantity()).isEqualTo(2);
    assertThat(quantity.options()).noneMatch(option -> option.code().equals("FREIGHT"));
    assertThat(quote(35000, 2, false, "2026-09-04T02:00:00Z", "fitness").options())
        .noneMatch(option -> option.code().equals("FREIGHT"));
    assertThat(quote(35001, 1, false, "2026-09-04T02:00:00Z", "books").options())
        .noneMatch(option -> option.code().equals("FREIGHT"));
    var freight = quote(35001, 1, false, "2026-09-04T02:00:00Z", "fitness");
    assertThat(option(freight, "FREIGHT").feeMinor()).isEqualTo(2900);
    assertThat(freight.options())
        .extracting(DeliveryOption::code)
        .containsExactly("STANDARD", "EXPRESS", "FREIGHT", "PICKUP");
  }

  @Test
  void datesUseShanghaiBusinessDaysAndExcludeTheRequestDay() {
    var friday = quote(100, 1, false, "2026-09-04T02:00:00Z", null);
    assertThat(option(friday, "STANDARD").earliestDate()).isEqualTo(LocalDate.of(2026, 9, 9));
    assertThat(option(friday, "STANDARD").latestDate()).isEqualTo(LocalDate.of(2026, 9, 11));
    assertThat(option(friday, "EXPRESS").earliestDate()).isEqualTo(LocalDate.of(2026, 9, 8));
    // UTC Sunday is already Monday in the store: the two-day express estimate is Wednesday.
    var shanghaiMonday = quote(100, 1, false, "2026-09-06T16:30:00Z", null);
    assertThat(option(shanghaiMonday, "EXPRESS").earliestDate())
        .isEqualTo(LocalDate.of(2026, 9, 9));
    assertThat(shanghaiMonday.timeZone()).isEqualTo("Asia/Shanghai");
  }

  @Test
  void pickupUsesOpeningAndClosingTimesWithoutInventingAStoreNearTheUser() {
    var early = quote(100, 1, false, "2026-09-04T00:00:00Z", null);
    assertThat(option(early, "PICKUP").readyAt()).isEqualTo(Instant.parse("2026-09-04T03:00:00Z"));
    var closesExactly = quote(100, 1, false, "2026-09-04T11:00:00Z", null);
    assertThat(option(closesExactly, "PICKUP").readyAt())
        .isEqualTo(Instant.parse("2026-09-04T13:00:00Z"));
    var tooLate = quote(100, 1, false, "2026-09-04T11:00:01Z", null);
    assertThat(option(tooLate, "PICKUP").readyAt())
        .isEqualTo(Instant.parse("2026-09-05T03:00:00Z"));
    assertThat(option(tooLate, "PICKUP").location()).isEqualTo("ShopMate 上海演示门店（徐汇区）");
  }

  @Test
  void emptyItemsOfferGeneralRulesAndRemainAnEstimate() {
    var request = new EstimateRequest(List.of());
    var estimate =
        service(request, List.of(), true, "2026-09-04T02:00:00Z").estimate("owner", request);
    assertThat(estimate.items()).isEmpty();
    assertThat(estimate.itemSubtotalMinor()).isZero();
    assertThat(estimate.estimateOnly()).isTrue();
    assertThat(estimate.options())
        .extracting(DeliveryOption::code)
        .containsExactly("STANDARD", "EXPRESS", "PICKUP");
    assertThat(option(estimate, "EXPRESS").feeMinor()).isEqualTo(999);
  }

  @Test
  void duplicatesUnsupportedCurrencyAndUnavailableRowsRejectTheWholeEstimate() {
    var request =
        new EstimateRequest(List.of(new EstimateItem("sku", 1), new EstimateItem("SKU", 1)));
    var duplicate =
        List.of(
            sku(0, "sku", 100, "CNY", 10, true, "PUBLISHED", null),
            sku(1, "sku", 100, "CNY", 10, true, "PUBLISHED", null));
    assertFailure(request, duplicate, "duplicate_sku");
    var single = new EstimateRequest(List.of(new EstimateItem("sku", 1)));
    assertFailure(
        single,
        List.of(sku(0, "sku", 100, "USD", 10, true, "PUBLISHED", null)),
        "unsupported_currency");
    assertFailure(single, List.of(sku(0, null, 0, null, 0, false, null, null)), "sku_unavailable");
    assertFailure(
        single, List.of(sku(0, "sku", 0, "CNY", 10, true, "PUBLISHED", null)), "sku_unavailable");
    assertFailure(
        single, List.of(sku(0, "sku", 100, "CNY", 0, true, "PUBLISHED", null)), "sku_unavailable");
    assertFailure(
        single,
        List.of(sku(0, "sku", 100, "CNY", 10, false, "PUBLISHED", null)),
        "sku_unavailable");
    assertFailure(
        single,
        List.of(sku(0, "sku", 100, "CNY", 10, true, "UNPUBLISHED", null)),
        "sku_unavailable");
  }

  @Test
  void arithmeticOverflowCannotProduceAnIncorrectFeeQuote() {
    var request = new EstimateRequest(List.of(new EstimateItem("sku", 2)));
    assertFailure(
        request,
        List.of(sku(0, "sku", Long.MAX_VALUE, "CNY", 10, true, "PUBLISHED", null)),
        "amount_out_of_range");
  }

  @Test
  void requestAndStoredRuleBoundariesAreExplicit() {
    assertThatThrownBy(() -> new EstimateRequest(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EstimateItem("sku", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EstimateItem("sku", 25))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new EstimateRequest(java.util.Collections.nCopies(101, new EstimateItem("sku", 1))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Pickup("Store", LocalTime.of(9, 0), LocalTime.of(10, 0), 120))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Standard(599, 4900, 5, 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Configuration configuration() {
    return new Configuration(
        7,
        "CNY",
        "Asia/Shanghai",
        new Rules(
            new Standard(599, 4900, 3, 5),
            new Express(999, 4900, 2),
            new Freight(2900, 5, 7, Set.of("office-electronics", "fitness"), 35000),
            new Pickup("ShopMate 上海演示门店（徐汇区）", LocalTime.of(9, 0), LocalTime.of(21, 0), 120)));
  }

  private static DeliveryEstimate quote(
      long price, int quantity, boolean member, String time, String category) {
    var request = new EstimateRequest(List.of(new EstimateItem("sku", quantity)));
    return service(
            request,
            List.of(sku(0, "sku", price, "CNY", 24, true, "PUBLISHED", category)),
            member,
            time)
        .estimate("owner", request);
  }

  private static RetailFulfillmentService service(
      EstimateRequest request, List<Sku> skus, boolean member, String time) {
    var repository = mock(RetailFulfillmentRepository.class);
    when(repository.configuration()).thenReturn(configuration());
    when(repository.skus(request.items())).thenReturn(skus);
    var preferences = mock(ShoppingPreferencesRepository.class);
    when(preferences.find("owner"))
        .thenReturn(new Preferences("owner", null, member ? "MEMBER" : "NONE", null, Map.of()));
    return new RetailFulfillmentService(
        repository, preferences, Clock.fixed(Instant.parse(time), ZoneOffset.UTC));
  }

  private static Sku sku(
      int index,
      String id,
      long price,
      String currency,
      long stock,
      boolean available,
      String publicationState,
      String category) {
    return new Sku(index, id, price, currency, stock, available, publicationState, 3, category);
  }

  private static DeliveryOption option(DeliveryEstimate estimate, String code) {
    return estimate.options().stream()
        .filter(option -> option.code().equals(code))
        .findFirst()
        .orElseThrow();
  }

  private static void assertFailure(EstimateRequest request, List<Sku> skus, String category) {
    assertThatThrownBy(
            () -> service(request, skus, false, "2026-09-04T02:00:00Z").estimate("owner", request))
        .isInstanceOfSatisfying(
            RetailFulfillmentException.class,
            exception -> assertThat(exception.category()).isEqualTo(category));
  }
}
