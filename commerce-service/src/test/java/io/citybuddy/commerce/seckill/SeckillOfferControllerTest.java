package io.citybuddy.commerce.seckill;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.citybuddy.commerce.catalog.CatalogException;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SeckillOfferControllerTest {
  private final DirectUserAuthorizer authorizer = mock(DirectUserAuthorizer.class);
  private final SeckillActivityRepository repository = mock(SeckillActivityRepository.class);
  private final MockMvc http;

  SeckillOfferControllerTest() {
    var properties =
        new SeckillOrderProperties(
            "seckill:reserve",
            "localhost:8081",
            "orders",
            "consumer",
            Duration.ofMinutes(15),
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            1);
    when(authorizer.authorize("Bearer buyer", null, "seckill:reserve"))
        .thenReturn(new DirectUserAuthorizer.DirectPrincipal("buyer", null, null));
    http =
        MockMvcBuilders.standaloneSetup(
                new SeckillReservationController(
                    authorizer, properties, mock(SeckillTransactionCoordinator.class), repository))
            .setControllerAdvice(new SeckillRequestExceptionHandler())
            .build();
  }

  @Test
  void requiresDirectUserBeforeReadingActivities() throws Exception {
    when(authorizer.authorize(null, null, "seckill:reserve"))
        .thenThrow(new CatalogException(401, "Missing bearer"));
    http.perform(get("/api/seckill/activities")).andExpect(status().isUnauthorized());
    verifyNoInteractions(repository);
  }

  @Test
  void boundsTheCatalogQuery() throws Exception {
    for (String limit : List.of("0", "51")) {
      http.perform(
              get("/api/seckill/activities")
                  .header("Authorization", "Bearer buyer")
                  .param("limit", limit))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(repository);
  }

  @Test
  void exposesTheActivityVersionAndIntegerDisplayPrice() throws Exception {
    when(repository.visibleOffers(20))
        .thenReturn(
            List.of(
                new SeckillOffer(
                    "a",
                    "p",
                    "Kettle",
                    12900,
                    "CNY",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2027-01-01T00:00:00Z"),
                    4)));
    http.perform(get("/api/seckill/activities").header("Authorization", "Bearer buyer"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activities[0].activityVersion").value(4))
        .andExpect(jsonPath("$.activities[0].unitPriceMinor").value(12900));
  }
}
