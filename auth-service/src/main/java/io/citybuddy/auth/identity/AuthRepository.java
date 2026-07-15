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

  public record UserCredential(
      String subject, String state, List<String> permissions, String passwordHash) {}

  public record ServiceCredential(
      String clientId, String state, List<String> allowedScopes, String credentialHash) {}

  public record KeyMetadata(String kid, String state, Instant activatedAt, Instant retireAfter) {}
}
