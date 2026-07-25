package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
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
    String query =
        "SELECT "
            + String.join(
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
                "succeeded_at")
            + " FROM mock_payment_attempt WHERE order_id = ? LIMIT 2 FOR UPDATE";
    when(jdbc.query(
            eq(query),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(ORDER_ID)))
        .thenReturn(java.util.List.of());

    assertThat(
            new MockPaymentRepository(jdbc).enumerateAttemptByOrderClosure(ORDER_ID, " FOR UPDATE"))
        .isEmpty();

    verify(jdbc)
        .query(
            eq(query),
            org.mockito.ArgumentMatchers.<RowMapper<MockPaymentRepository.AttemptRecord>>any(),
            eq(ORDER_ID));
    verifyNoMoreInteractions(jdbc);
  }
}
