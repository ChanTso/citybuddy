package io.citybuddy.commerce.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.catalog.ProductPublicationService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MerchantProductOperationsTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ProductPublicationService publication = mock(ProductPublicationService.class);
  private final MerchantProductOperations operations =
      new MerchantProductOperations(jdbc, mapper, publication);

  @Test
  void inventoryRejectsFractionalZeroNegativeAndExcessiveRestocksBeforeAnyDatabaseCall()
      throws Exception {
    for (String quantity : new String[] {"0", "-1", "501", "1.5", "\"10\"", "null"}) {
      var payload =
          mapper.readTree(
              "{\"items\":[{\"listingId\":\"sku\",\"action\":\"restock\",\"quantity\":"
                  + quantity
                  + "}]}");
      assertThatThrownBy(() -> operations.prepare("INVENTORY_ACTION", payload))
          .isInstanceOfSatisfying(
              MerchantException.class, exception -> assertThat(exception.status()).isEqualTo(400));
    }
    verifyNoInteractions(jdbc, publication);
  }

  @Test
  void inventoryCannotSmuggleAmountsIntoPauseOrUseUnknownActionFields() throws Exception {
    for (String value :
        new String[] {
          "{\"items\":[{\"listingId\":\"sku\",\"action\":\"pause\",\"quantity\":10}]}",
          "{\"items\":[{\"listingId\":\"sku\",\"action\":\"publish\"}]}",
          "{\"items\":[],\"owner\":\"other\"}",
          "{\"items\":[]}"
        }) {
      var payload = mapper.readTree(value);
      assertThatThrownBy(() -> operations.prepare("INVENTORY_ACTION", payload))
          .isInstanceOf(MerchantException.class);
    }
    verifyNoInteractions(jdbc, publication);
  }

  @Test
  void malformedPersistedSnapshotsAreServiceErrorsRatherThanBusinessRejections() throws Exception {
    var snapshot = mapper.readTree("{\"kind\":\"INVENTORY_ACTION\",\"products\":\"not-a-list\"}");
    assertThatThrownBy(() -> operations.apply("INVENTORY_ACTION", snapshot))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(jdbc, publication);
  }
}
