package io.citybuddy.commerce.refund;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

final class RefundRequestParser {
  static final int MAXIMUM_REQUEST_BYTES = 4096;

  private final ObjectMapper objectMapper;

  RefundRequestParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  RefundRequest parse(HttpServletRequest request) {
    try {
      long declaredLength = request.getContentLengthLong();
      if (declaredLength > MAXIMUM_REQUEST_BYTES) {
        throw invalid();
      }
      byte[] body = request.getInputStream().readNBytes(MAXIMUM_REQUEST_BYTES + 1);
      if (body.length == 0 || body.length > MAXIMUM_REQUEST_BYTES) {
        throw invalid();
      }
      try (var parser = objectMapper.getFactory().createParser(body)) {
        parser.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
        JsonNode value = objectMapper.readTree(parser);
        if (value == null
            || !value.isObject()
            || parser.nextToken() != null
            || !integral(value, "amountMinor")
            || !textual(value, "currency")
            || !optionalTextual(value, "userSubject")
            || !fieldsAllowed(value)) {
          throw invalid();
        }
        return objectMapper.treeToValue(value, RefundRequest.class);
      }
    } catch (RefundException exception) {
      throw exception;
    } catch (Exception exception) {
      throw invalid();
    }
  }

  private static boolean integral(JsonNode value, String field) {
    JsonNode item = value.get(field);
    return item != null && item.isIntegralNumber() && item.canConvertToLong();
  }

  private static boolean textual(JsonNode value, String field) {
    JsonNode item = value.get(field);
    return item != null && item.isTextual();
  }

  private static boolean optionalTextual(JsonNode value, String field) {
    JsonNode item = value.get(field);
    return item == null || item.isNull() || item.isTextual();
  }

  private static boolean fieldsAllowed(JsonNode value) {
    Set<String> allowed = Set.of("amountMinor", "currency", "userSubject");
    return value.properties().stream().allMatch(entry -> allowed.contains(entry.getKey()));
  }

  private static RefundException invalid() {
    return new RefundException(400, "VALIDATION", "Refund request is invalid");
  }
}
