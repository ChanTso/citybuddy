package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class MockPaymentRequestParserTest {
  private final MockPaymentRequestParser parser = new MockPaymentRequestParser(new ObjectMapper());

  @Test
  void acceptsOneStrictStartObject() {
    MockPaymentRequest request =
        parser.start(request("{\"amountMinor\":100,\"currency\":\"AUD\",\"userSubject\":null}"));

    assertThat(request.amountMinor()).isEqualTo(100);
    assertThat(request.currency()).isEqualTo("AUD");
  }

  @Test
  void rejectsDuplicateFieldsTrailingValuesAndWrongPrimitiveTypes() {
    assertInvalid(request("{\"amountMinor\":100,\"amountMinor\":101,\"currency\":\"AUD\"}"), true);
    assertInvalid(request("{\"amountMinor\":100,\"currency\":\"AUD\"} {}"), true);
    assertInvalid(request("{\"amountMinor\":\"100\",\"currency\":\"AUD\"}"), true);
    assertInvalid(request("{\"amountMinor\":100,\"currency\":7}"), true);
  }

  @Test
  void rejectsEmptyNullArrayAndMalformedUtf8() {
    assertInvalid(request(""), true);
    assertInvalid(request("null"), true);
    assertInvalid(request("[]"), true);
    MockHttpServletRequest malformed = new MockHttpServletRequest();
    malformed.setContent(new byte[] {(byte) 0xc3, (byte) 0x28});
    assertInvalid(malformed, true);
  }

  @Test
  void rejectsBodiesAtTheAcquisitionBoundary() {
    MockHttpServletRequest declared = request("{}");
    declared.addHeader("Content-Length", MockPaymentRequestParser.MAXIMUM_REQUEST_BYTES + 1);
    assertInvalid(declared, true);

    assertInvalid(request(" ".repeat(MockPaymentRequestParser.MAXIMUM_REQUEST_BYTES + 1)), true);
  }

  @Test
  void callbackUsesTheSameTotalBoundary() {
    MockPaymentCallbackRequest callback =
        parser.callback(
            request(
                """
                {
                  "callbackEventId":"00000000-0000-0000-0000-000000000001",
                  "callbackCorrelationId":"00000000-0000-0000-0000-000000000002",
                  "orderId":"00000000-0000-0000-0000-000000000003",
                  "amountMinor":100,
                  "currency":"AUD",
                  "outcome":"SUCCEEDED"
                }
                """));
    assertThat(callback.amountMinor()).isEqualTo(100);

    assertInvalid(
        request(
            """
            {
              "callbackEventId":"00000000-0000-0000-0000-000000000001",
              "callbackCorrelationId":"00000000-0000-0000-0000-000000000002",
              "orderId":"00000000-0000-0000-0000-000000000003",
              "amountMinor":100,
              "amountMinor":101,
              "currency":"AUD",
              "outcome":"SUCCEEDED"
            }
            """),
        false);
  }

  private void assertInvalid(MockHttpServletRequest request, boolean start) {
    assertThatThrownBy(
            () -> {
              if (start) {
                parser.start(request);
              } else {
                parser.callback(request);
              }
            })
        .isInstanceOf(MockPaymentException.class)
        .satisfies(
            failure -> {
              MockPaymentException payment = (MockPaymentException) failure;
              assertThat(payment.status()).isEqualTo(400);
              assertThat(payment.category()).isEqualTo("VALIDATION");
            });
  }

  private static MockHttpServletRequest request(String body) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContentType("application/json");
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    return request;
  }
}
