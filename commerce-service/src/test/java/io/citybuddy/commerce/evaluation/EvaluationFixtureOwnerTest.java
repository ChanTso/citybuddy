package io.citybuddy.commerce.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EvaluationFixtureOwnerTest {
  private static final String HANDLE = "A".repeat(43);

  @Test
  void totalParserAndStrictParserShareTheCanonicalTransformation() {
    assertThat(EvaluationSandboxRepository.tryFixtureOwner(HANDLE))
        .contains("eval-handle:" + HANDLE);
    assertThat(EvaluationSandboxRepository.fixtureOwner(HANDLE))
        .isEqualTo(EvaluationSandboxRepository.tryFixtureOwner(HANDLE).orElseThrow());
  }

  @Test
  void totalParserConcealsEveryMalformedHandleWithoutWeakeningTheStrictBoundary() {
    assertThat(EvaluationSandboxRepository.tryFixtureOwner(null)).isEmpty();
    assertThat(EvaluationSandboxRepository.tryFixtureOwner("A".repeat(42))).isEmpty();
    assertThat(EvaluationSandboxRepository.tryFixtureOwner("A".repeat(44))).isEmpty();
    assertThat(EvaluationSandboxRepository.tryFixtureOwner("A".repeat(42) + "!")).isEmpty();

    assertThatThrownBy(() -> EvaluationSandboxRepository.fixtureOwner(null))
        .isInstanceOf(EvaluationSandboxException.class);
    assertThatThrownBy(() -> EvaluationSandboxRepository.fixtureOwner("A".repeat(42)))
        .isInstanceOf(EvaluationSandboxException.class);
    assertThatThrownBy(() -> EvaluationSandboxRepository.fixtureOwner("A".repeat(44)))
        .isInstanceOf(EvaluationSandboxException.class);
    assertThatThrownBy(() -> EvaluationSandboxRepository.fixtureOwner("A".repeat(42) + "!"))
        .isInstanceOf(EvaluationSandboxException.class);
  }
}
