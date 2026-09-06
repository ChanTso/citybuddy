package io.citybuddy.commerce.retail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.catalog.CatalogException;
import io.citybuddy.commerce.catalog.CatalogExceptionHandler;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RetailCatalogControllerTest {
  private final DirectUserAuthorizer authorizer = mock(DirectUserAuthorizer.class);
  private final RetailCatalogService catalog = mock(RetailCatalogService.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(
              new RetailCatalogController(authorizer, catalog, new ObjectMapper()))
          .setControllerAdvice(new CatalogExceptionHandler())
          .build();

  @Test
  void authenticatesBeforeParsingAndDoesNotTurnMissingIdentityIntoBadSearch() throws Exception {
    doThrow(new CatalogException(401, "Missing identity"))
        .when(authorizer)
        .authorize(null, null, "catalog:read");
    mvc.perform(post("/api/retail/products/search").content("not-json"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(catalog);
  }

  @Test
  void rejectsAmbiguousOrUnboundedSearchInputBeforeQuerying() throws Exception {
    for (String body :
        List.of(
            "null",
            "[]",
            "{} {}",
            "{\"limit\":1,\"limit\":2}",
            "{\"limit\":0}",
            "{\"limit\":51}",
            "{\"limit\":1.5}",
            "{\"maxPriceMinor\":100}",
            "{\"currency\":\"CNY\",\"maxPriceMinor\":1.5}",
            "{\"unexpected\":true}",
            "{\"sort\":\"SQL injection\"}")) {
      mvc.perform(
              post("/api/retail/products/search")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(post("/api/retail/products/search").content("x".repeat(8193)))
        .andExpect(status().isPayloadTooLarge());
    verifyNoInteractions(catalog);
  }

  @Test
  void acceptsDefaultSearchAndReturnsMissingProductWithoutAStub() throws Exception {
    when(catalog.search(any())).thenReturn(List.of());
    when(catalog.find("missing")).thenReturn(Optional.empty());
    mvc.perform(
            post("/api/retail/products/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/retail/products/missing")).andExpect(status().isNotFound());
  }
}
