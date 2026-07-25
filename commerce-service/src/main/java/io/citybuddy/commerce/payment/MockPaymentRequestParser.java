package io.citybuddy.commerce.payment;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;

final class MockPaymentRequestParser {
  static final int MAXIMUM_REQUEST_BYTES = 4096;

  private final ObjectMapper objectMapper;

  MockPaymentRequestParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  MockPaymentRequest start(HttpServletRequest request) {
    return parse(request, MockPaymentRequest.class, "Payment request is invalid");
  }

  MockPaymentCallbackRequest callback(HttpServletRequest request) {
    return parse(request, MockPaymentCallbackRequest.class, "Payment callback is invalid");
  }

  private <T> T parse(HttpServletRequest request, Class<T> type, String message) {
    try {
      long declaredLength = request.getContentLengthLong();
      if (declaredLength > MAXIMUM_REQUEST_BYTES) {
        throw invalid(message);
      }
      byte[] body = request.getInputStream().readNBytes(MAXIMUM_REQUEST_BYTES + 1);
      if (body.length == 0 || body.length > MAXIMUM_REQUEST_BYTES) {
        throw invalid(message);
      }
      try (var parser = objectMapper.getFactory().createParser(body)) {
        parser.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
        JsonNode value = objectMapper.readTree(parser);
        if (value == null || !value.isObject() || parser.nextToken() != null) {
          throw invalid(message);
        }
        if (!validShape(value, type)) {
          throw invalid(message);
        }
        return objectMapper.treeToValue(value, type);
      }
    } catch (MockPaymentException exception) {
      throw exception;
    } catch (Exception exception) {
      throw invalid(message);
    }
  }

  private static boolean validShape(JsonNode value, Class<?> type) {
    if (type == MockPaymentRequest.class) {
      return integral(value, "amountMinor")
          && textual(value, "currency")
          && optionalTextual(value, "userSubject");
    }
    if (type == MockPaymentCallbackRequest.class) {
      return textual(value, "callbackEventId")
          && textual(value, "callbackCorrelationId")
          && textual(value, "orderId")
          && integral(value, "amountMinor")
          && textual(value, "currency")
          && textual(value, "outcome")
          && optionalTextual(value, "sandboxId")
          && optionalTextual(value, "supportSessionId")
          && optionalTextual(value, "traceId")
          && optionalTextual(value, "operationId");
    }
    throw new IllegalArgumentException("Unsupported mock-payment request type");
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

  private static MockPaymentException invalid(String message) {
    return new MockPaymentException(400, "VALIDATION", message);
  }
}
