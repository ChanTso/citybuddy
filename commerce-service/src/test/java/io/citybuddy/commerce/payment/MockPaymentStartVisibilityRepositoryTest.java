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
      EvaluationPaymentCommittedFaces.standardOrderByIdSql("") + " AND sandbox_id = ? FOR UPDATE";

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
                .enumerateStartOrderVisibility(ORDER_ID, USER, SANDBOX, " FOR UPDATE"))
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
                .enumerateStartOrderVisibility(ORDER_ID, USER, SANDBOX, " FOR UPDATE"))
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
}
