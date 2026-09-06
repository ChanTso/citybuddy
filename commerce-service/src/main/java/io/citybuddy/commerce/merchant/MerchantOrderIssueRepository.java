package io.citybuddy.commerce.merchant;

import io.citybuddy.commerce.shopping.OrderFulfillmentRepository;
import io.citybuddy.commerce.shopping.ShoppingOrderModels.FulfillmentFacts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public final class MerchantOrderIssueRepository {
  private final JdbcTemplate jdbc;
  private final OrderFulfillmentRepository fulfillment;

  public MerchantOrderIssueRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.fulfillment = new OrderFulfillmentRepository(jdbc);
  }

  public List<OrderIssue> list(int limit) {
    List<OrderIssue> issues =
        jdbc.query(
            """
        SELECT i.*, o.product_id,
          CASE WHEN i.kind = 'return_spike' THEN (
            SELECT COUNT(DISTINCT r.order_id)
            FROM mock_refund r
            JOIN standard_order refunded
              ON refunded.order_id = r.order_id AND r.order_kind = 'STANDARD'
             AND BINARY refunded.user_subject = BINARY r.user_subject
             AND refunded.sandbox_id IS NULL AND refunded.status = 'PAID'
            JOIN mock_payment_attempt p ON p.attempt_id = r.payment_attempt_id
             AND p.order_id = refunded.order_id AND p.order_kind = 'STANDARD'
             AND BINARY p.user_subject = BINARY refunded.user_subject
             AND p.sandbox_id IS NULL AND p.state = 'SUCCEEDED'
             AND p.currency = refunded.currency AND p.amount_minor = refunded.total_price_minor
            WHERE refunded.product_id = o.product_id AND r.currency = p.currency
              AND r.state IN ('REQUESTED', 'PROCESSING', 'SUCCEEDED')
              AND r.created_at >= i.window_start AND r.created_at < i.window_end
          ) END AS refund_order_count
        FROM retail_order_issue i JOIN standard_order o ON o.order_id = i.order_id
        WHERE i.resolved_at IS NULL AND o.sandbox_id IS NULL
        ORDER BY i.opened_at DESC, i.issue_id LIMIT ?
        """,
            (row, index) ->
                new OrderIssue(
                    row.getString("issue_id"),
                    row.getString("order_id"),
                    row.getString("kind"),
                    row.getString("summary"),
                    row.getString("product_id"),
                    row.getString("buyer_message_excerpt"),
                    row.getTimestamp("opened_at").toInstant(),
                    null,
                    row.getObject("refund_order_count", Long.class),
                    instant(row.getTimestamp("window_start")),
                    instant(row.getTimestamp("window_end")),
                    row.getString("source_kind"),
                    row.getString("source_ref")),
            limit);
    var facts = fulfillment.find(issues.stream().map(OrderIssue::orderId).distinct().toList());
    return issues.stream()
        .map(
            issue -> {
              FulfillmentFacts delivery = facts.get(issue.orderId());
              if (issue.kind().equals("delayed")
                  && (delivery == null
                      || delivery.delayReason() == null
                      || delivery.delayReason().isBlank())) {
                throw new IllegalStateException(
                    "Delayed order issue has no supporting fulfillment fact");
              }
              String summary =
                  issue.kind().equals("return_spike")
                      ? issue.refundRequestedOrderCount()
                          + " paid orders have refund requests in this window"
                      : issue.summary();
              return new OrderIssue(
                  issue.issueId(),
                  issue.orderId(),
                  issue.kind(),
                  summary,
                  issue.listingId(),
                  issue.buyerMessageExcerpt(),
                  issue.openedAt(),
                  delivery,
                  issue.refundRequestedOrderCount(),
                  issue.windowStart(),
                  issue.windowEnd(),
                  issue.sourceKind(),
                  issue.sourceRef());
            })
        .toList();
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  public record OrderIssue(
      String issueId,
      String orderId,
      String kind,
      String summary,
      String listingId,
      String buyerMessageExcerpt,
      Instant openedAt,
      FulfillmentFacts fulfillment,
      Long refundRequestedOrderCount,
      Instant windowStart,
      Instant windowEnd,
      String sourceKind,
      String sourceRef) {}
}
