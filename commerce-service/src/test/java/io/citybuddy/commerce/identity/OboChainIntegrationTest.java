package io.citybuddy.commerce.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class OboChainIntegrationTest {

  @Test
  void authorizesRealLoginSessionAndExchangeChain() {
    String obo = System.getenv("IDENTITY_PROBE_OBO");
    String direct = System.getenv("IDENTITY_PROBE_DIRECT");
    String jwksUrl = System.getenv("IDENTITY_JWKS_URL");
    String session = System.getenv("SUPPORT_SESSION_ID");
    Assumptions.assumeTrue(obo != null, "Run through scripts/test_identity_integration.sh");

    HttpClient client = HttpClient.newHttpClient();
    JwksLoader loader =
        () -> {
          try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUrl)).GET().build();
            HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
              throw new IllegalStateException("JWKS request failed");
            }
            return response.body();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("JWKS request interrupted", exception);
          } catch (Exception exception) {
            throw new IllegalStateException("JWKS request failed", exception);
          }
        };
    OboAuthorizer authorizer =
        new OboAuthorizer(
            new OboProperties(
                "https://identity.citybuddy.test",
                jwksUrl,
                Duration.ofSeconds(30),
                Duration.ofSeconds(60)),
            loader,
            Clock.systemUTC());
    OboAuthorizer.AuthorizationRequest expected =
        new OboAuthorizer.AuthorizationRequest(
            "catalog:read", "user-integration", session, null, null, null);

    OboAuthorizer.OboPrincipal principal = authorizer.authorize(obo, expected);

    assertThat(principal.subject()).isEqualTo("user-integration");
    assertThat(principal.sessionId()).isEqualTo(session);
    assertThatThrownBy(() -> authorizer.authorize(direct, expected))
        .isInstanceOf(OboAuthorizationException.class);
    assertThatThrownBy(
            () ->
                authorizer.authorize(
                    obo,
                    new OboAuthorizer.AuthorizationRequest(
                        "catalog:read", "user-integration", session, "other-user", null, null)))
        .isInstanceOf(OboAuthorizationException.class)
        .hasMessage("Body identity substitution");
    assertThatThrownBy(
            () ->
                authorizer.authorize(
                    obo,
                    new OboAuthorizer.AuthorizationRequest(
                        "catalog:read",
                        "user-integration",
                        session,
                        null,
                        null,
                        "forbidden-production-context")))
        .isInstanceOf(OboAuthorizationException.class)
        .hasMessageContaining("Evaluation");
  }
}
