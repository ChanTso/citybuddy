package io.citybuddy.commerce.action;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActionCanonicalTest {
  @Test
  void lengthPrefixMakesCanonicalCommitmentStableAndBoundarySafe() {
    assertThat(ActionCanonical.hash("ab", "c"))
        .isEqualTo(ActionCanonical.hash("ab", "c"))
        .isNotEqualTo(ActionCanonical.hash("a", "bc"))
        .matches("[0-9a-f]{64}");
  }
}
