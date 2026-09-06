package io.citybuddy.commerce.faq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FaqFixturePublisherCliTest {
  private static final String VALID =
      """
      [{"faqId":"retail-policy-returns","question":"退换货？","answer":"请先查询已支付的本人订单。"}]
      """;

  @Test
  void readsUnicodePolicyContentWithoutCoercingOrChangingIt() throws Exception {
    assertThat(FaqFixturePublisherCli.readEntries(input(VALID)))
        .containsExactly(
            new FaqFixturePublisher.Entry("retail-policy-returns", "退换货？", "请先查询已支付的本人订单。"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "{}",
        "null",
        "[null]",
        "[{\"faqId\":\"policy\",\"question\":12,\"answer\":\"answer\"}]",
        "[{\"faqId\":\"policy\",\"question\":\"question\",\"answer\":true}]",
        "[{\"faqId\":\"policy\",\"question\":\"question\",\"answer\":\"answer\",\"other\":0}]",
        "[{\"faqId\":\"policy\",\"question\":\"question\"}]",
        "[{\"faqId\":\"policy\",\"question\":\"question\",\"answer\":\"first\",\"answer\":\"second\"}]",
        "[] []"
      })
  void malformedShapeAndDuplicateKeysFailBeforeAnyDatabaseAccess(String json) {
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    assertThat(
            FaqFixturePublisherCli.run(
                new String[0],
                input(json),
                new PrintStream(output),
                new PrintStream(error),
                Map.of()))
        .isEqualTo(2);
    assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(error.toString(StandardCharsets.UTF_8)).isEqualTo("{\"error\":\"INVALID_INPUT\"}\n");
  }

  @Test
  void oversizedInputAndInvalidEntryAreRejected() {
    assertThatThrownBy(() -> FaqFixturePublisherCli.readEntries(input(" ".repeat(1_048_577))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                FaqFixturePublisherCli.readEntries(
                    input(VALID.replace("retail-policy-returns", "../bad"))))
        .isInstanceOf(FaqPublicationException.class);
  }

  @Test
  void databaseFailureDoesNotEchoCredentialsOrConnectionDetails() {
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    assertThat(
            FaqFixturePublisherCli.run(
                new String[0],
                input(VALID),
                new PrintStream(output),
                new PrintStream(error),
                Map.of(
                    "SPRING_DATASOURCE_URL",
                    "jdbc:unavailable:private-host",
                    "SPRING_DATASOURCE_USERNAME",
                    "fixture-operator",
                    "SPRING_DATASOURCE_PASSWORD",
                    "private-value")))
        .isEqualTo(4);
    assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(error.toString(StandardCharsets.UTF_8))
        .isEqualTo("{\"error\":\"DATABASE_ERROR\"}\n");
  }

  @Test
  void missingConfigurationAndCommandLineArgumentsHaveExplicitFailureCategories() {
    var error = new ByteArrayOutputStream();
    assertThat(
            FaqFixturePublisherCli.run(
                new String[0], input(VALID), System.out, new PrintStream(error), Map.of()))
        .isEqualTo(2);
    assertThat(error.toString(StandardCharsets.UTF_8))
        .isEqualTo("{\"error\":\"MISSING_DATABASE_CONFIGURATION\"}\n");
    error.reset();
    assertThat(
            FaqFixturePublisherCli.run(
                new String[] {"private-value"},
                input(VALID),
                System.out,
                new PrintStream(error),
                Map.of()))
        .isEqualTo(2);
    assertThat(error.toString(StandardCharsets.UTF_8))
        .isEqualTo("{\"error\":\"INVALID_ARGUMENTS\"}\n");
  }

  private static ByteArrayInputStream input(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }
}
