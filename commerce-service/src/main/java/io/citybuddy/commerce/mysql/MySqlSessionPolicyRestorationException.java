package io.citybuddy.commerce.mysql;

import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Signals that a transaction could not restore the JDBC session policy before pool return.
 *
 * <p>This provenance must remain distinct from ordinary row-lock competition even when the restore
 * failure's SQL cause uses a contention vendor code.
 */
public final class MySqlSessionPolicyRestorationException
    extends DataAccessResourceFailureException {
  public MySqlSessionPolicyRestorationException(Throwable cause) {
    super("MySQL session policy restoration failed", cause);
  }
}
