package io.citybuddy.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StandardOrderIntentCommitmentTest {
  @Test
  void preservesTheOriginalCanonicalKnownAnswer() {
    assertThat(StandardOrderIntentCommitment.CANONICALIZER_ID)
        .isEqualTo("STANDARD_ORDER_INTENT_V1");
    assertThat(StandardOrderIntentCommitment.hash("product-1", 1, 7))
        .isEqualTo("1b278d0cd4d0e30dcdd8704c14125a68ab85edef30a000a13693c0420197de05");
    assertThat(StandardOrderIntentCommitment.hash(" product-1 ", 1, 7))
        .isEqualTo("1b278d0cd4d0e30dcdd8704c14125a68ab85edef30a000a13693c0420197de05");
  }
}
