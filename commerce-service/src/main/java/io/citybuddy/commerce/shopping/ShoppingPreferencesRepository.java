package io.citybuddy.commerce.shopping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.shopping.ShoppingPreferencesModels.Preferences;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

public final class ShoppingPreferencesRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public ShoppingPreferencesRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public Preferences find(String owner) {
    var profiles =
        jdbc.query(
            """
        SELECT display_name, loyalty_tier, default_location, preferences
        FROM crm_profile WHERE user_subject = ? AND BINARY user_subject = BINARY ?
        """,
            (row, number) ->
                new Preferences(
                    owner,
                    row.getString("display_name"),
                    row.getString("loyalty_tier"),
                    row.getString("default_location"),
                    preferences(row.getString("preferences"))),
            owner,
            owner);
    return profiles.stream()
        .findFirst()
        .orElseGet(() -> new Preferences(owner, null, "NONE", null, Map.of()));
  }

  private Map<String, String> preferences(String encoded) {
    JsonNode value;
    try {
      value = mapper.readTree(encoded);
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw new IllegalStateException("Invalid stored shopping preferences", exception);
    }
    if (value == null || !value.isObject() || value.size() > 20) {
      throw new IllegalStateException("Invalid stored shopping preferences");
    }
    Map<String, String> result = new LinkedHashMap<>();
    var fields = value.fields();
    while (fields.hasNext()) {
      var field = fields.next();
      if (field.getKey().isEmpty()
          || field.getKey().length() > 64
          || !field.getValue().isTextual()
          || field.getValue().textValue().length() > 500) {
        throw new IllegalStateException("Invalid stored shopping preferences");
      }
      result.put(field.getKey(), field.getValue().textValue());
    }
    return result;
  }
}
