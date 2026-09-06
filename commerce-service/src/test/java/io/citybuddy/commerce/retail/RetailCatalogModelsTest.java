package io.citybuddy.commerce.retail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.retail.RetailCatalogModels.Search;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetailCatalogModelsTest {
  @Test
  void omittedJsonLimitDefaultsButExplicitZeroDoesNot() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    Search request = mapper.readValue("{}", Search.class);

    assertEquals(20, request.limit());
    assertEquals(0, request.offset());
    assertEquals("relevance", request.sort());
    assertEquals(Map.of(), request.attributes());
    assertThrows(
        IllegalArgumentException.class, () -> search(null, null, null, null, null, null, 0));
  }

  @Test
  void offsetsAreBoundedAndLegacyConstructorStillStartsAtZero() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    assertEquals(10000, mapper.readValue("{\"offset\":10000}", Search.class).offset());
    assertEquals(0, search(null, null, null, null, null, null, 50).offset());
    for (int offset : new int[] {-1, 10001}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new Search(null, null, null, null, null, null, null, null, 50, offset));
    }
  }

  @Test
  void moneyRequiresAnExplicitCurrencyAndAnOrderedNonnegativeRange() {
    assertThrows(
        IllegalArgumentException.class, () -> search(0L, null, null, null, null, null, 20));
    assertThrows(
        IllegalArgumentException.class,
        () -> search(null, null, null, null, "price_asc", null, 20));
    assertThrows(
        IllegalArgumentException.class, () -> search(-1L, 100L, null, "CNY", null, null, 20));
    assertThrows(
        IllegalArgumentException.class, () -> search(101L, 100L, null, "CNY", null, null, 20));
    assertEquals("CNY", search(0L, 100L, null, " cny ", "price_asc", null, 20).currency());
  }

  @Test
  void nonFiniteRatingsAndUnboundedAttributeInputsAreRejected() {
    for (double rating : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -0.1, 5.1}) {
      assertThrows(
          IllegalArgumentException.class, () -> search(null, null, rating, null, null, null, 20));
    }
    Map<String, String> attributes = new HashMap<>();
    attributes.put("size", null);
    assertThrows(
        IllegalArgumentException.class, () -> search(null, null, null, null, null, attributes, 20));
    attributes.clear();
    for (int index = 0; index < 11; index++) {
      attributes.put("key" + index, "value");
    }
    assertThrows(
        IllegalArgumentException.class, () -> search(null, null, null, null, null, attributes, 20));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Search("x".repeat(257), null, null, null, null, null, null, null, null));
  }

  private static Search search(
      Long minimum,
      Long maximum,
      Double rating,
      String currency,
      String sort,
      Map<String, String> attributes,
      Integer limit) {
    return new Search(null, null, minimum, maximum, rating, currency, attributes, sort, limit);
  }
}
