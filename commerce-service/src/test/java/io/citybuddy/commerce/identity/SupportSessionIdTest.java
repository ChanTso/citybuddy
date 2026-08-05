package io.citybuddy.commerce.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupportSessionIdTest {
  @Test
  void acceptsEveryDeterministicBase64UrlFirstCharacter() {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    for (int firstSixBits = 0; firstSixBits < 64; firstSixBits++) {
      byte[] raw = new byte[32];
      raw[0] = (byte) (firstSixBits << 2);
      String value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

      assertThat(value).hasSize(43);
      assertThat(value.charAt(0)).isEqualTo(alphabet.charAt(firstSixBits));
      assertThat(SupportSessionId.isValid(value)).isTrue();
    }
  }

  @Test
  void preservesLegacyCompatibilityAndAcceptsOnlyExactOpaqueEdges() {
    assertThat(
            List.of("session-main", "session.payment:1", "A", "a.b_c:d-e").stream()
                .allMatch(SupportSessionId::isValid))
        .isTrue();

    String leadingDash = "-" + "A".repeat(42);
    String leadingUnderscore = "_" + "A".repeat(42);
    assertThat(leadingDash).hasSize(43);
    assertThat(leadingUnderscore).hasSize(43);
    assertThat(SupportSessionId.isValid(leadingDash)).isTrue();
    assertThat(SupportSessionId.isValid(leadingUnderscore)).isTrue();
  }

  @Test
  void rejectsWhitespaceControlsWrongLengthEdgesAndNonUrlAlphabet() {
    List<String> invalid =
        java.util.Arrays.asList(
            null,
            "",
            " ",
            " session-main",
            "session-main ",
            "session main",
            ".session-main",
            ":session-main",
            "-" + "A".repeat(41),
            "-" + "A".repeat(43),
            "_" + "A".repeat(41),
            "_" + "A".repeat(43),
            "session+main",
            "session/main",
            "session=main",
            "session\nmain",
            "session\u0000main",
            "sessіon-main",
            "A".repeat(65),
            "session-main\r\nX-Injected: true");

    assertThat(invalid).allMatch(value -> !SupportSessionId.isValid(value));
  }
}
