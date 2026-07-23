package io.citybuddy.commerce.order;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OrderRepositoryTest {
  @Test
  void boundedIdempotencyReadRestoresThePooledSessionAfterLockTimeout() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Long.class))
        .thenReturn(50L);
    CannotAcquireLockException timeout =
        new CannotAcquireLockException("controlled lock wait timeout");
    OrderRepository repository = new OrderRepository(jdbc, new ObjectMapper());

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(
              () ->
                  repository.withLockWaitTimeout(
                      1,
                      () -> {
                        throw timeout;
                      }))
          .isSameAs(timeout);
    } finally {
      TransactionSynchronizationManager.clear();
    }

    var ordered = inOrder(jdbc);
    ordered.verify(jdbc).queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Long.class);
    ordered.verify(jdbc).execute("SET SESSION innodb_lock_wait_timeout = 1");
    ordered.verify(jdbc).execute("SET SESSION innodb_lock_wait_timeout = 50");
  }

  @Test
  void boundedIdempotencyReadRejectsAnUnsafeTimeoutBeforeUsingJdbc() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OrderRepository repository = new OrderRepository(jdbc, new ObjectMapper());

    assertThatThrownBy(() -> repository.withLockWaitTimeout(0, () -> null))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(jdbc);
  }

  @Test
  void lockWaitGuardRejectsUseOutsideATransactionBeforeUsingJdbc() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OrderRepository repository = new OrderRepository(jdbc, new ObjectMapper());

    assertThatThrownBy(() -> repository.withLockWaitTimeout(1, () -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active transaction");

    verifyNoInteractions(jdbc);
  }
}
