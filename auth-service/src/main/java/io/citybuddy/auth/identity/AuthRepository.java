package io.citybuddy.auth.identity;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class AuthRepository {
  private final JdbcClient jdbc;

  public AuthRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<UserCredential> findUser(String loginIdentifier) {
    return jdbc.sql(
            """
            SELECT p.subject, p.state, p.permissions, c.password_hash
              FROM auth_user_principal p
              JOIN auth_login_credential c ON c.principal_id = p.principal_id
             WHERE p.login_identifier = :login
            """)
        .param("login", loginIdentifier)
        .query(
            (rs, row) ->
                new UserCredential(
                    rs.getString("subject"),
                    rs.getString("state"),
                    split(rs.getString("permissions")),
                    rs.getString("password_hash")))
        .optional();
  }

  public Optional<ServiceCredential> findService(String clientId) {
    return jdbc.sql(
            """
            SELECT client_id, state, allowed_scopes, credential_hash
              FROM auth_service_identity
             WHERE client_id = :clientId
            """)
        .param("clientId", clientId)
        .query(
            (rs, row) ->
                new ServiceCredential(
                    rs.getString("client_id"),
                    rs.getString("state"),
                    split(rs.getString("allowed_scopes")),
                    rs.getString("credential_hash")))
        .optional();
  }

  public boolean isActiveSubject(String subject) {
    return jdbc.sql(
                """
            SELECT COUNT(*)
              FROM auth_user_principal
             WHERE subject = :subject AND state = 'ACTIVE'
            """)
            .param("subject", subject)
            .query(Integer.class)
            .single()
        == 1;
  }

  public Optional<EvaluationPrincipal> findEvaluationByProvisionKey(String idempotencyKey) {
    return evaluationQuery("provision_idempotency_key = :value", idempotencyKey);
  }

  public Optional<EvaluationPrincipal> findEvaluationBySandboxCase(
      String sandboxId, String caseCorrelation) {
    return jdbc.sql(
            evaluationSelect()
                + " WHERE sandbox_id = :sandboxId AND case_correlation = :caseCorrelation")
        .param("sandboxId", sandboxId)
        .param("caseCorrelation", caseCorrelation)
        .query(EvaluationPrincipal.class)
        .optional();
  }

  public Optional<EvaluationPrincipal> findEvaluationByHandle(String handle) {
    return evaluationQuery("opaque_handle = :value", handle);
  }

  public int insertEvaluationPrincipal(EvaluationPrincipal principal) {
    return jdbc.sql(
            """
            INSERT IGNORE INTO auth_eval_test_principal (
              provisioning_id, opaque_handle, subject, sandbox_id, case_correlation,
              test_user_label, permissions, provision_idempotency_key, ttl_seconds,
              state, expires_at
            ) VALUES (
              :provisioningId, :opaqueHandle, :subject, :sandboxId, :caseCorrelation,
              :testUserLabel, :permissions, :provisionIdempotencyKey, :ttlSeconds,
              'PROVISIONED', :expiresAt
            )
            """)
        .param("provisioningId", principal.provisioningId())
        .param("opaqueHandle", principal.opaqueHandle())
        .param("subject", principal.subject())
        .param("sandboxId", principal.sandboxId())
        .param("caseCorrelation", principal.caseCorrelation())
        .param("testUserLabel", principal.testUserLabel())
        .param("permissions", principal.permissions())
        .param("provisionIdempotencyKey", principal.provisionIdempotencyKey())
        .param("ttlSeconds", principal.ttlSeconds())
        .param("expiresAt", principal.expiresAt())
        .update();
  }

  public int revokeEvaluationPrincipal(
      String handle,
      String sandboxId,
      String caseCorrelation,
      String revokeIdempotencyKey,
      Instant revokedAt) {
    return jdbc.sql(
            """
            UPDATE auth_eval_test_principal
               SET state = 'REVOKED',
                   revoke_idempotency_key = :revokeIdempotencyKey,
                   revoked_at = :revokedAt
             WHERE opaque_handle = :handle
               AND sandbox_id = :sandboxId
               AND case_correlation = :caseCorrelation
               AND state = 'PROVISIONED'
            """)
        .param("revokeIdempotencyKey", revokeIdempotencyKey)
        .param("revokedAt", revokedAt)
        .param("handle", handle)
        .param("sandboxId", sandboxId)
        .param("caseCorrelation", caseCorrelation)
        .update();
  }

  public boolean isActiveEvaluationSubject(String subject, String sandboxId, Instant now) {
    return jdbc.sql(
                """
                SELECT COUNT(*)
                  FROM auth_eval_test_principal
                 WHERE subject = :subject
                   AND sandbox_id = :sandboxId
                   AND state = 'PROVISIONED'
                   AND expires_at > :now
                """)
            .param("subject", subject)
            .param("sandboxId", sandboxId)
            .param("now", now)
            .query(Integer.class)
            .single()
        == 1;
  }

  public List<KeyMetadata> publicKeyMetadata() {
    return jdbc.sql(
            """
            SELECT kid, state, activated_at, retire_after
              FROM auth_signing_key_metadata
             WHERE state = 'CURRENT'
                OR (state = 'OVERLAP' AND retire_after > CURRENT_TIMESTAMP(6))
             ORDER BY state, kid
            """)
        .query(
            (rs, row) ->
                new KeyMetadata(
                    rs.getString("kid"),
                    rs.getString("state"),
                    rs.getTimestamp("activated_at").toInstant(),
                    rs.getTimestamp("retire_after") == null
                        ? null
                        : rs.getTimestamp("retire_after").toInstant()))
        .list();
  }

  private static List<String> split(String value) {
    return Arrays.stream(value.trim().split("\\s+"))
        .filter(item -> !item.isBlank())
        .distinct()
        .toList();
  }

  private Optional<EvaluationPrincipal> evaluationQuery(String predicate, String value) {
    return jdbc.sql(evaluationSelect() + " WHERE " + predicate)
        .param("value", value)
        .query(EvaluationPrincipal.class)
        .optional();
  }

  private static String evaluationSelect() {
    return """
        SELECT provisioning_id, opaque_handle, subject, sandbox_id, case_correlation,
               test_user_label, permissions, provision_idempotency_key, ttl_seconds,
               state, expires_at, revoke_idempotency_key, revoked_at
          FROM auth_eval_test_principal
        """;
  }

  public record UserCredential(
      String subject, String state, List<String> permissions, String passwordHash) {}

  public record ServiceCredential(
      String clientId, String state, List<String> allowedScopes, String credentialHash) {}

  public record KeyMetadata(String kid, String state, Instant activatedAt, Instant retireAfter) {}

  public record EvaluationPrincipal(
      String provisioningId,
      String opaqueHandle,
      String subject,
      String sandboxId,
      String caseCorrelation,
      String testUserLabel,
      String permissions,
      String provisionIdempotencyKey,
      int ttlSeconds,
      String state,
      Instant expiresAt,
      String revokeIdempotencyKey,
      Instant revokedAt) {
    public List<String> permissionList() {
      return split(permissions);
    }
  }
}
