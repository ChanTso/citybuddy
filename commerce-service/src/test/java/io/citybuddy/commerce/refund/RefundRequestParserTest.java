package io.citybuddy.commerce.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RefundRequestParserTest {
  private final RefundRequestParser parser = new RefundRequestParser(new ObjectMapper());

  @Test
  void acceptsOneStrictRefundObject() {
    RefundRequest request =
        parser.parse(request("{\"amountMinor\":100,\"currency\":\"AUD\",\"userSubject\":null}"));

    assertThat(request.amountMinor()).isEqualTo(100);
    assertThat(request.currency()).isEqualTo("AUD");
  }

  @Test
  void rejectsDuplicateTrailingWrongShapeAndMalformedEncoding() {
    assertInvalid("{\"amountMinor\":100,\"amountMinor\":101,\"currency\":\"AUD\"}");
    assertInvalid("{\"amountMinor\":100,\"currency\":\"AUD\"} {}");
    assertInvalid("{\"amountMinor\":\"100\",\"currency\":\"AUD\"}");
    assertInvalid("{\"amountMinor\":100,\"currency\":\"AUD\",\"extra\":\"value\"}");
    assertInvalid("null");
    MockHttpServletRequest malformed = new MockHttpServletRequest();
    malformed.setContent(new byte[] {(byte) 0xc3, (byte) 0x28});
    assertInvalid(malformed);
  }

  @Test
  void rejectsBeforeMaterializingMoreThanTheRequestBound() {
    MockHttpServletRequest declared = request("{}");
    declared.addHeader("Content-Length", RefundRequestParser.MAXIMUM_REQUEST_BYTES + 1);
    assertInvalid(declared);
    assertInvalid(request(" ".repeat(RefundRequestParser.MAXIMUM_REQUEST_BYTES + 1)));
  }

  private void assertInvalid(String body) {
    assertInvalid(request(body));
  }

  private void assertInvalid(MockHttpServletRequest request) {
    assertThatThrownBy(() -> parser.parse(request))
        .isInstanceOf(RefundException.class)
        .satisfies(
            failure -> {
              RefundException refund = (RefundException) failure;
              assertThat(refund.status()).isEqualTo(400);
              assertThat(refund.category()).isEqualTo("VALIDATION");
              assertThat(refund.getMessage()).isEqualTo("Refund request is invalid");
            });
  }

  private static MockHttpServletRequest request(String body) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContentType("application/json");
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    return request;
  }
}
