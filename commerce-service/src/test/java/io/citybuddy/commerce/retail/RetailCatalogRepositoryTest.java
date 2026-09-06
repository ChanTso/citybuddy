package io.citybuddy.commerce.retail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.retail.RetailCatalogModels.View;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RetailCatalogRepositoryTest {
  @Test
  void familyUsesPurchasablePriceAndReturnsActualSkuStates() throws Exception {
    var repository =
        repository(
            row("small", "family", 100, "CNY", 0, true),
            row("paused", "family", 150, "CNY", 8, false),
            row("large", "family", 200, "CNY", 3, true));

    View family = repository.find("family").orElseThrow();

    assertEquals("family", family.kind());
    assertNull(family.productId());
    assertNull(family.publicationVersion());
    assertEquals(200, family.priceMinor());
    assertEquals(11, family.stockQuantity());
    assertTrue(family.inStock());
    assertEquals(
        List.of("small", "paused", "large"),
        family.variants().stream().map(View::productId).toList());
    View paused = family.variants().get(1);
    assertEquals(150, paused.priceMinor());
    assertEquals(8, paused.stockQuantity());
    assertFalse(paused.inStock());
    assertEquals(7L, paused.publicationVersion());
    assertEquals(2L, paused.metadataVersion());
    assertEquals(3L, paused.familyMetadataVersion());
  }

  @Test
  void exhaustedFamilyFallsBackToCheapestPublishedVariant() throws Exception {
    var repository =
        repository(
            row("small", "family", 100, "CNY", 0, true),
            row("large", "family", 200, "CNY", 0, true));

    View family = repository.find("family").orElseThrow();

    assertEquals(100, family.priceMinor());
    assertFalse(family.inStock());
    assertEquals(2, family.variants().size());
  }

  @Test
  void mixedCurrencyOrOverlappingIdentityCannotBecomeAMisleadingFamily() throws Exception {
    var mixed =
        repository(
            row("small", "family", 100, "CNY", 1, true),
            row("large", "family", 200, "USD", 1, true));
    assertThrows(IllegalStateException.class, () -> mixed.find("family"));

    var overlapping =
        repository(
            row("family", null, 100, "CNY", 1, true), row("large", "family", 200, "CNY", 1, true));
    assertThrows(IllegalStateException.class, () -> overlapping.find("family"));
  }

  @Test
  void directVariantDetailsKeepTheSkuIdentityAndTradeVersion() throws Exception {
    var repository = repository(row("large", "family", 200, "CNY", 3, true));

    View variant = repository.find("large").orElseThrow();

    assertEquals("variant", variant.kind());
    assertEquals("large", variant.id());
    assertEquals("large", variant.productId());
    assertEquals("family", variant.variantOf());
    assertEquals("SKU large", variant.title());
    assertEquals("Product description", variant.shortDescription());
    assertEquals("Full content", variant.content().path("longDescription").asText());
    assertTrue(variant.variants().isEmpty());
  }

  @SuppressWarnings("unchecked")
  private static RetailCatalogRepository repository(ResultSet... rows) {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              RowMapper<Object> rowMapper = invocation.getArgument(1);
              List<Object> result = new ArrayList<>();
              for (int index = 0; index < rows.length; index++) {
                when(rows[index].getString("lookup_id")).thenReturn(invocation.getArgument(2));
                result.add(rowMapper.mapRow(rows[index], index));
              }
              return result;
            });
    return new RetailCatalogRepository(jdbc, new ObjectMapper());
  }

  private static ResultSet row(
      String id, String family, long price, String currency, long stock, boolean available)
      throws Exception {
    ResultSet row = mock(ResultSet.class);
    when(row.getString("product_id")).thenReturn(id);
    when(row.getString("name")).thenReturn("SKU " + id);
    when(row.getString("description")).thenReturn("Product description");
    when(row.getLong("price_minor")).thenReturn(price);
    when(row.getString("currency")).thenReturn(currency);
    when(row.getLong("stock_quantity")).thenReturn(stock);
    when(row.getBoolean("available")).thenReturn(available);
    when(row.getLong("publication_version")).thenReturn(7L);
    when(row.getString("family_id")).thenReturn(family);
    when(row.getLong("metadata_version")).thenReturn(2L);
    when(row.getString("family_name")).thenReturn("Family");
    when(row.getString("family_description")).thenReturn("Family description");
    when(row.getObject("family_metadata_version", Long.class))
        .thenReturn(family == null ? null : 3L);
    when(row.getString("content")).thenReturn("{\"longDescription\":\"Full content\"}");
    when(row.getString("family_content")).thenReturn("{}");
    when(row.getString("family_options"))
        .thenReturn("[{\"name\":\"size\",\"values\":[\"small\",\"large\"]}]");
    when(row.getString("option_values")).thenReturn("{\"size\":\"large\"}");
    return row;
  }
}
