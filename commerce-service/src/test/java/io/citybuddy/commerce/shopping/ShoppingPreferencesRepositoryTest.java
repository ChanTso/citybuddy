package io.citybuddy.commerce.shopping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.shopping.ShoppingPreferencesModels.Preferences;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ShoppingPreferencesRepositoryTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final ShoppingPreferencesRepository repository =
      new ShoppingPreferencesRepository(jdbc, mapper);

  @Test
  void missingProfileUsesTheActualOwnerWithoutCreatingAProfile() {
    Preferences missing = repository.find("Buyer-Case");
    assertThat(missing).isEqualTo(new Preferences("Buyer-Case", null, "NONE", null, Map.of()));
    verify(jdbc)
        .query(
            contains("BINARY user_subject = BINARY ?"),
            any(RowMapper.class),
            eq("Buyer-Case"),
            eq("Buyer-Case"));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void mapsOnlyBoundedStringPreferencesAndNeverReturnsEmail() throws Exception {
    mapped("{\"style\":\"neutral\",\"budget\":\"\"}");
    Preferences value = repository.find("buyer");
    assertThat(value.preferences())
        .containsExactlyInAnyOrderEntriesOf(Map.of("style", "neutral", "budget", ""));
    assertThat(value.loyaltyTier()).isEqualTo("MEMBER");
    assertThatThrownBy(() -> value.preferences().put("unsafe", "mutation"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void malformedPersistenceIsNotReportedAsEmptyPreferences() throws Exception {
    Map<String, String> tooMany = new LinkedHashMap<>();
    for (int i = 0; i < 21; i++) {
      tooMany.put("k" + i, "v");
    }
    for (String encoded :
        List.of(
            "broken",
            "[]",
            "null",
            "{\"x\":1}",
            "{\"x\":null}",
            "{\"\":\"x\"}",
            mapper.writeValueAsString(Map.of("x".repeat(65), "v")),
            mapper.writeValueAsString(Map.of("x", "v".repeat(501))),
            mapper.writeValueAsString(tooMany))) {
      mapped(encoded);
      assertThatThrownBy(() -> repository.find("buyer"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Invalid stored shopping preferences");
    }
  }

  private void mapped(String encoded) throws Exception {
    ResultSet row = mock(ResultSet.class);
    when(row.getString("display_name")).thenReturn("Buyer");
    when(row.getString("loyalty_tier")).thenReturn("MEMBER");
    when(row.getString("default_location")).thenReturn("上海市徐汇区");
    when(row.getString("preferences")).thenReturn(encoded);
    doAnswer(
            invocation -> List.of(invocation.<RowMapper<Preferences>>getArgument(1).mapRow(row, 0)))
        .when(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<Preferences>>any(),
            eq("buyer"),
            eq("buyer"));
  }
}
