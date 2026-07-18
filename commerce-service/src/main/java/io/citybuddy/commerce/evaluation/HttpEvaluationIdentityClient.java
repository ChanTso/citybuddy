package io.citybuddy.commerce.evaluation;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class HttpEvaluationIdentityClient implements EvaluationIdentityClient {
  private final RestClient client;
  private final EvaluationSandboxProperties properties;

  public HttpEvaluationIdentityClient(RestClient client, EvaluationSandboxProperties properties) {
    this.client = client;
    this.properties = properties;
  }

  @Override
  public Provisioned provision(
      String sandboxId,
      String caseCorrelation,
      String testUserLabel,
      int ttlSeconds,
      String idempotencyKey) {
    try {
      ProvisionResponse response =
          client
              .post()
              .uri("/internal/eval/test-principals/provision")
              .headers(this::authenticate)
              .header("Idempotency-Key", idempotencyKey)
              .body(
                  Map.of(
                      "sandboxId", sandboxId,
                      "caseCorrelation", caseCorrelation,
                      "testUserLabel", testUserLabel,
                      "ttlSeconds", ttlSeconds))
              .retrieve()
              .body(ProvisionResponse.class);
      if (response == null || response.handle() == null || response.expiresAt() == null) {
        throw new RestClientException("Invalid provisioning response");
      }
      return new Provisioned(response.handle(), response.expiresAt());
    } catch (RestClientException exception) {
      throw new EvaluationIdentityUnavailableException(exception);
    }
  }

  @Override
  public void revoke(
      String handle, String sandboxId, String caseCorrelation, String idempotencyKey) {
    try {
      RevokeResponse response =
          client
              .post()
              .uri("/internal/eval/test-principals/{handle}/revoke", handle)
              .headers(this::authenticate)
              .header("Idempotency-Key", idempotencyKey)
              .body(Map.of("sandboxId", sandboxId, "caseCorrelation", caseCorrelation))
              .retrieve()
              .body(RevokeResponse.class);
      if (response == null
          || !handle.equals(response.handle())
          || !"REVOKED".equals(response.state())) {
        throw new RestClientException("Invalid revocation response");
      }
    } catch (RestClientException exception) {
      throw new EvaluationIdentityUnavailableException(exception);
    }
  }

  private void authenticate(HttpHeaders headers) {
    headers.setBasicAuth(properties.authClientId(), properties.authClientSecret());
  }

  private record ProvisionResponse(String handle, Instant expiresAt) {}

  private record RevokeResponse(String handle, String state) {}

  public static final class EvaluationIdentityUnavailableException extends RuntimeException {
    EvaluationIdentityUnavailableException(Throwable cause) {
      super("Evaluation identity operation failed", cause);
    }
  }
}
