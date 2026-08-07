package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class SeckillOpenApiContractTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void publicReservationStatesAndDecisionsMatchRuntimeEnums() throws IOException {
    JsonNode reservation;
    try (InputStream openApi = getClass().getResourceAsStream("/openapi.json")) {
      assertThat(openApi).isNotNull();
      reservation =
          objectMapper
              .readTree(openApi)
              .path("components")
              .path("schemas")
              .path("SeckillReservation");
    }

    JsonNode stateEnum = reservation.path("properties").path("state").path("enum");
    Set<String> runtimeStates = enumNames(ReservationState.values());
    assertThat(elements(stateEnum)).allMatch(JsonNode::isTextual);
    assertThat(elements(stateEnum)).hasSize(runtimeStates.size());
    assertThat(stringValues(stateEnum)).containsExactlyInAnyOrderElementsOf(runtimeStates);

    JsonNode projectionVersion = reservation.path("properties").path("projectionVersion");
    assertThat(projectionVersion.path("minimum").asLong()).isEqualTo(1);
    assertThat(projectionVersion.path("maximum").asLong()).isEqualTo(4);

    JsonNode decisionCode = reservation.path("properties").path("decisionCode");
    JsonNode decisionEnum = decisionCode.path("enum");
    Set<String> runtimeDecisions = enumNames(ReservationDecisionCode.values());
    assertThat(elements(decisionEnum)).allMatch(value -> value.isTextual() || value.isNull());
    assertThat(elements(decisionEnum)).anyMatch(JsonNode::isNull);
    assertThat(elements(decisionEnum)).hasSize(runtimeDecisions.size() + 1);
    assertThat(stringValues(decisionEnum)).containsExactlyInAnyOrderElementsOf(runtimeDecisions);
    assertThat(stringValues(decisionCode.path("type"))).containsExactlyInAnyOrder("string", "null");
  }

  private static Set<String> enumNames(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> stringValues(JsonNode values) {
    return elements(values).stream()
        .filter(JsonNode::isTextual)
        .map(JsonNode::textValue)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static List<JsonNode> elements(JsonNode values) {
    return StreamSupport.stream(values.spliterator(), false).toList();
  }
}
