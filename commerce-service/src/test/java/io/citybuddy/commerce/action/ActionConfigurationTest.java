package io.citybuddy.commerce.action;

import static org.assertj.core.api.Assertions.assertThat;

import io.citybuddy.commerce.evaluation.EvaluationSandboxProperties;
import org.junit.jupiter.api.Test;

class ActionConfigurationTest {
  @Test
  void ownershipAblationRequiresEvaluationProperties() {
    EvaluationSandboxProperties defaultEvaluation = evaluationProperties(null);
    EvaluationSandboxProperties ablatedEvaluation = evaluationProperties(false);

    assertThat(ActionConfiguration.ownershipBindingEnabled(null)).isTrue();
    assertThat(ActionConfiguration.ownershipBindingEnabled(defaultEvaluation)).isTrue();
    assertThat(ActionConfiguration.ownershipBindingEnabled(ablatedEvaluation)).isFalse();
    assertThat(ActionService.effectiveOwnershipBinding(null, false)).isTrue();
    assertThat(ActionService.effectiveOwnershipBinding("evaluation-sandbox", false)).isFalse();
  }

  private static EvaluationSandboxProperties evaluationProperties(Boolean ownershipBinding) {
    return new EvaluationSandboxProperties(
        "manager",
        "manager-secret",
        "https://auth.test",
        "commerce-service",
        "commerce-secret",
        "https://identity.test",
        "citybuddy-web",
        "https://auth.test/auth/jwks",
        null,
        null,
        null,
        null,
        null,
        null,
        0,
        0,
        null,
        null,
        ownershipBinding);
  }
}
