package io.citybuddy.commerce.tool;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentToolControllerTest {
  private OboAuthorizer authorizer;
  private JdbcTemplate jdbc;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    authorizer = mock(OboAuthorizer.class);
    jdbc = mock(JdbcTemplate.class);
    mvc = MockMvcBuilders.standaloneSetup(new AgentToolController(authorizer, jdbc)).build();
  }

  @Test
  @SuppressWarnings("unchecked")
  void returnsOnlyTheBoundedPublishedProductView() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), eq("product-1")))
        .thenReturn(
            List.of(
                Map.of(
                    "productId",
                    "product-1",
                    "name",
                    "Tea",
                    "priceMinor",
                    500L,
                    "currency",
                    "CNY",
                    "available",
                    true,
                    "publicationVersion",
                    2L)));

    mvc.perform(
            post("/internal/tools/catalog.product.get")
                .header("Authorization", "Bearer signed-obo")
                .header("X-Support-Session-Id", "session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"product-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.productId").value("product-1"))
        .andExpect(jsonPath("$.description").doesNotExist())
        .andExpect(jsonPath("$.stockQuantity").doesNotExist());

    verify(authorizer)
        .authorize(
            eq("signed-obo"),
            eq(
                new OboAuthorizer.AuthorizationRequest(
                    "catalog:read", null, "session-1", null, null, null)));
  }

  @Test
  void rejectsUnknownFieldsBeforeCommerceRead() throws Exception {
    mvc.perform(
            post("/internal/tools/catalog.product.get")
                .header("Authorization", "Bearer signed-obo")
                .header("X-Support-Session-Id", "session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"product-1\",\"scope\":\"catalog:*\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(jdbc);
  }

  @Test
  void boundsOboRejectionWithoutLeakingClaimDetails() throws Exception {
    doThrow(new OboAuthorizationException("private token claim detail"))
        .when(authorizer)
        .authorize(anyString(), any(OboAuthorizer.AuthorizationRequest.class));

    mvc.perform(
            post("/internal/tools/catalog.product.get")
                .header("Authorization", "Bearer direct-user-token")
                .header("X-Support-Session-Id", "session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"product-1\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Forbidden"));

    verifyNoInteractions(jdbc);
  }
}
