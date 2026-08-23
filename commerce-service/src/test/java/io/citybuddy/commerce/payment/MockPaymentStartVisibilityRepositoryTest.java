package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MockPaymentStartVisibilityRepositoryTest {
  private static final String ORDER_ID = "00000000-0000-0000-0000-000000000120";
  private static final String USER = "payment-owner";
  private static final String SANDBOX = "sandbox-payment";
  private static final String QUERY =
      EvaluationPaymentCommittedFaces.standardOrderByIdSql("") + " AND sandbox_id = ?";
  private static final String ATTEMPT_COLUMNS =
      String.join(
          ", ",
          "attempt_id",
          "callback_correlation_id",
          "user_subject",
          "order_id",
          "order_kind",
          "sandbox_id",
          "request_idempotency_key",
          "intent_hash",
          "amount_minor",
          "refunded_amount_minor",
          "currency",
          "state",
          "state_version",
          "succeeded_at");

  @Test
  void unknownAndMalformedCandidatesUseTheSameOneQueryShape() {
    JdbcTemplate unknownJdbc = mock(JdbcTemplate.class);
    when(unknownJdbc.query(
            eq(QUERY),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.OrderTruth>>any(),
            eq(ORDER_ID),
            eq(SANDBOX)))
        .thenReturn(java.util.List.of());

    assertThat(
            new MockPaymentRepository(unknownJdbc)
                .enumerateStartOrderVisibility(ORDER_ID, USER, SANDBOX))
        .isEmpty();
    verify(unknownJdbc)
        .query(
            eq(QUERY),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.OrderTruth>>any(),
            eq(ORDER_ID),
            eq(SANDBOX));
    verifyNoMoreInteractions(unknownJdbc);

    JdbcTemplate malformedJdbc = mock(JdbcTemplate.class);
    MockPaymentRepository.OrderTruth malformed =
        new MockPaymentRepository.OrderTruth(
            "STANDARD",
            ORDER_ID,
            "eval-handle:" + "A".repeat(43),
            SANDBOX,
            "short",
            "payment-product",
            null,
            null,
            null,
            null,
            1800,
            "AUD",
            "UNPAID",
            1);
    when(malformedJdbc.query(
            eq(QUERY),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.OrderTruth>>any(),
            eq(ORDER_ID),
            eq(SANDBOX)))
        .thenReturn(java.util.List.of(malformed));

    assertThat(
            new MockPaymentRepository(malformedJdbc)
                .enumerateStartOrderVisibility(ORDER_ID, USER, SANDBOX))
        .singleElement()
        .isInstanceOf(PaymentStartOrderVisibility.Concealed.class);
    verify(malformedJdbc)
        .query(
            eq(QUERY),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.OrderTruth>>any(),
            eq(ORDER_ID),
            eq(SANDBOX));
    verifyNoMoreInteractions(malformedJdbc);
  }

  @Test
  void cardinalityEnumerationStopsAfterTheSecondRowBeforeLocking() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    String requestKey = "payment-request-index";
    String requestQuery =
        "SELECT "
            + ATTEMPT_COLUMNS
            + " FROM mock_payment_attempt FORCE INDEX (uq_mock_payment_request)"
            + " WHERE user_subject = ? AND request_idempotency_key = ? LIMIT 2";
    String query =
        "SELECT "
            + ATTEMPT_COLUMNS
            + " FROM mock_payment_attempt FORCE INDEX (uq_mock_payment_order)"
            + " WHERE order_id = ? LIMIT 2 FOR UPDATE";
    String ownedQuery =
        "SELECT "
            + ATTEMPT_COLUMNS
            + " FROM mock_payment_attempt FORCE INDEX (uq_mock_payment_order)"
            + " WHERE order_id = ? AND user_subject = ? LIMIT 2 FOR UPDATE";
    when(jdbc.query(
            eq(query),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(ORDER_ID)))
        .thenReturn(java.util.List.of());
    when(jdbc.query(
            eq(ownedQuery),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(ORDER_ID),
            eq(USER)))
        .thenReturn(java.util.List.of());
    when(jdbc.query(
            eq(requestQuery),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(USER),
            eq(requestKey)))
        .thenReturn(java.util.List.of());

    assertThat(
            new MockPaymentRepository(jdbc).enumerateStartAttemptVisibility(USER, requestKey, ""))
        .isEmpty();
    assertThat(
            new MockPaymentRepository(jdbc).enumerateAttemptByOrderClosure(ORDER_ID, " FOR UPDATE"))
        .isEmpty();
    assertThat(
            new MockPaymentRepository(jdbc)
                .enumerateOwnedAttemptByOrderVisibility(ORDER_ID, USER, " FOR UPDATE"))
        .isEmpty();

    verify(jdbc)
        .query(
            eq(requestQuery),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(USER),
            eq(requestKey));
    verify(jdbc)
        .query(
            eq(query),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(ORDER_ID));
    verify(jdbc)
        .query(
            eq(ownedQuery),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(ORDER_ID),
            eq(USER));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void attemptClosureUsesBoundedIndexedReadsAndExcludesTheRowAlreadyFound() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    MockPaymentRepository.AttemptRecord first =
        attempt(
            "00000000-0000-0000-0000-000000000121",
            "00000000-0000-0000-0000-000000000122",
            "00000000-0000-0000-0000-000000000123",
            "attempt-closure-first");
    MockPaymentRepository.AttemptRecord second =
        attempt(
            "00000000-0000-0000-0000-000000000124",
            "00000000-0000-0000-0000-000000000125",
            first.orderId(),
            "attempt-closure-second");
    String primaryQuery =
        "SELECT "
            + ATTEMPT_COLUMNS
            + " FROM mock_payment_attempt FORCE INDEX (PRIMARY)"
            + " WHERE attempt_id = ? LIMIT 2 FOR UPDATE";
    String correlationQuery =
        "SELECT "
            + ATTEMPT_COLUMNS
            + " FROM mock_payment_attempt FORCE INDEX (uq_mock_payment_callback_correlation)"
            + " WHERE callback_correlation_id = ? AND attempt_id <> ? LIMIT 1 FOR UPDATE";
    String orderQuery =
        "SELECT "
            + ATTEMPT_COLUMNS
            + " FROM mock_payment_attempt FORCE INDEX (uq_mock_payment_order)"
            + " WHERE order_id = ? AND attempt_id <> ? LIMIT 1 FOR UPDATE";
    when(jdbc.query(
            eq(primaryQuery),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(first.attemptId())))
        .thenReturn(java.util.List.of(first));
    when(jdbc.query(
            eq(correlationQuery),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(first.callbackCorrelationId()),
            eq(first.attemptId())))
        .thenReturn(java.util.List.of());
    when(jdbc.query(
            eq(orderQuery),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(first.orderId()),
            eq(first.attemptId())))
        .thenReturn(java.util.List.of(second));

    assertThat(new MockPaymentRepository(jdbc).enumerateAttemptClosure(first, " FOR UPDATE"))
        .containsExactly(first, second);

    org.mockito.InOrder queries = inOrder(jdbc);
    queries
        .verify(jdbc)
        .query(
            eq(primaryQuery),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(first.attemptId()));
    queries
        .verify(jdbc)
        .query(
            eq(correlationQuery),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(first.callbackCorrelationId()),
            eq(first.attemptId()));
    queries
        .verify(jdbc)
        .query(
            eq(orderQuery),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(first.orderId()),
            eq(first.attemptId()));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void attemptClosureStopsAfterTwoDistinctRows() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    MockPaymentRepository.AttemptRecord first =
        attempt(
            "00000000-0000-0000-0000-000000000126",
            "00000000-0000-0000-0000-000000000127",
            "00000000-0000-0000-0000-000000000128",
            "attempt-bound-first");
    MockPaymentRepository.AttemptRecord second =
        attempt(
            "00000000-0000-0000-0000-000000000129",
            first.callbackCorrelationId(),
            "00000000-0000-0000-0000-000000000130",
            "attempt-bound-second");
    String primaryQuery =
        "SELECT "
            + ATTEMPT_COLUMNS
            + " FROM mock_payment_attempt FORCE INDEX (PRIMARY)"
            + " WHERE attempt_id = ? LIMIT 2 FOR UPDATE";
    String correlationQuery =
        "SELECT "
            + ATTEMPT_COLUMNS
            + " FROM mock_payment_attempt FORCE INDEX (uq_mock_payment_callback_correlation)"
            + " WHERE callback_correlation_id = ? AND attempt_id <> ? LIMIT 1 FOR UPDATE";
    when(jdbc.query(
            eq(primaryQuery),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(first.attemptId())))
        .thenReturn(java.util.List.of(first));
    when(jdbc.query(
            eq(correlationQuery),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(first.callbackCorrelationId()),
            eq(first.attemptId())))
        .thenReturn(java.util.List.of(second));

    assertThat(new MockPaymentRepository(jdbc).enumerateAttemptClosure(first, " FOR UPDATE"))
        .containsExactly(first, second);

    verify(jdbc)
        .query(
            eq(primaryQuery),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(first.attemptId()));
    verify(jdbc)
        .query(
            eq(correlationQuery),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(first.callbackCorrelationId()),
            eq(first.attemptId()));
    verifyNoMoreInteractions(jdbc);
  }

  private static MockPaymentRepository.AttemptRecord attempt(
      String attemptId, String callbackCorrelationId, String orderId, String requestKey) {
    return MockPaymentRepository.AttemptRecord.pending(
        attemptId,
        callbackCorrelationId,
        USER,
        orderId,
        "STANDARD",
        null,
        requestKey,
        "a".repeat(64),
        1800,
        "AUD");
  }
}
