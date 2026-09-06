package io.citybuddy.commerce.merchant;

import io.citybuddy.commerce.catalog.ProductRepository.PriceChangeResult;
import io.citybuddy.commerce.merchant.MerchantMarketingModels.Campaign;
import io.citybuddy.commerce.merchant.MerchantMarketingModels.CampaignSnapshot;
import io.citybuddy.commerce.merchant.MerchantMarketingModels.Promotion;
import io.citybuddy.commerce.merchant.MerchantMarketingModels.PromotionSnapshot;
import io.citybuddy.commerce.merchant.MerchantMarketingModels.PromotionTarget;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class MerchantMarketingRepository {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public MerchantMarketingRepository(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public List<Campaign> campaigns(int limit, int offset) {
    bounds(limit, offset);
    return jdbc.query(
        "SELECT * FROM retail_campaign ORDER BY created_at DESC,campaign_id LIMIT ? OFFSET ?",
        this::mapCampaign,
        limit,
        offset);
  }

  public Optional<Campaign> campaign(String id) {
    return campaign(id, false);
  }

  Optional<Campaign> campaign(String id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM retail_campaign WHERE campaign_id=?" + (lock ? " FOR UPDATE" : ""),
            this::mapCampaign,
            id)
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Promotion> promotions(int limit, int offset) {
    bounds(limit, offset);
    return hydrate(
        jdbc.query(
            "SELECT * FROM retail_promotion ORDER BY created_at DESC,promotion_id LIMIT ? OFFSET ?",
            this::mapPromotion,
            limit,
            offset));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Optional<Promotion> promotion(String id) {
    return hydrate(
            jdbc.query(
                "SELECT * FROM retail_promotion WHERE promotion_id=?", this::mapPromotion, id))
        .stream()
        .findFirst();
  }

  String insertPromotion(
      PromotionSnapshot snapshot,
      List<PriceChangeResult> changes,
      String sourceChangeId,
      Instant now) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        """
        INSERT INTO retail_promotion
        (promotion_id,name,currency,discount_basis_points,starts_at,ends_at,state,version,
         created_at,updated_at,applied_at,source_change_id)
        VALUES (?,?,?,?,?,?,'APPLIED',1,?,?,?,?)
        """,
        id,
        snapshot.name(),
        snapshot.currency(),
        snapshot.discountBasisPoints(),
        timestamp(snapshot.startsAt()),
        timestamp(snapshot.endsAt()),
        timestamp(now),
        timestamp(now),
        timestamp(now),
        sourceChangeId);
    for (PriceChangeResult change : changes) {
      jdbc.update(
          """
          INSERT INTO retail_promotion_item
          (promotion_id,product_id,approved_base_price_minor,promotion_price_minor,before_version,after_version,event_id)
          VALUES (?,?,?,?,?,?,?)
          """,
          id,
          change.productId(),
          change.oldPriceMinor(),
          change.newPriceMinor(),
          change.oldVersion(),
          change.newVersion(),
          change.eventId());
    }
    return id;
  }

  Campaign writeCampaign(CampaignSnapshot snapshot, String changeId, Instant now) {
    if (snapshot.expectedVersion() == 0) {
      jdbc.update(
          """
          INSERT INTO retail_campaign
          (campaign_id,name,objective,audience,copy_text,channel,currency,budget_minor,starts_at,ends_at,
           state,version,created_at,updated_at,source_change_id)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,1,?,?,?)
          """,
          snapshot.campaignId(),
          snapshot.name(),
          snapshot.objective(),
          snapshot.audience(),
          snapshot.copyText(),
          snapshot.channel(),
          snapshot.currency(),
          snapshot.budgetMinor(),
          timestamp(snapshot.startsAt()),
          timestamp(snapshot.endsAt()),
          snapshot.state(),
          timestamp(now),
          timestamp(now),
          changeId);
    } else {
      // Attribution observations describe imported business facts, not this plan edit.
      int updated =
          jdbc.update(
              """
          UPDATE retail_campaign SET name=?,objective=?,audience=?,copy_text=?,channel=?,currency=?,
          budget_minor=?,starts_at=?,ends_at=?,state=?,version=?,updated_at=?,source_change_id=?
          WHERE campaign_id=? AND version=?
          """,
              snapshot.name(),
              snapshot.objective(),
              snapshot.audience(),
              snapshot.copyText(),
              snapshot.channel(),
              snapshot.currency(),
              snapshot.budgetMinor(),
              timestamp(snapshot.startsAt()),
              timestamp(snapshot.endsAt()),
              snapshot.state(),
              Math.addExact(snapshot.expectedVersion(), 1),
              timestamp(now),
              changeId,
              snapshot.campaignId(),
              snapshot.expectedVersion());
      if (updated != 1) {
        throw new IllegalStateException("Locked campaign changed during its update");
      }
    }
    return campaign(snapshot.campaignId()).orElseThrow();
  }

  private List<Promotion> hydrate(List<Promotion> rows) {
    if (rows.isEmpty()) {
      return rows;
    }
    Map<String, List<PromotionTarget>> targets = new LinkedHashMap<>();
    String parameters = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));
    jdbc.query(
        """
        SELECT i.*,p.price_minor AS current_price,p.publication_version AS current_version,
               p.currency AS current_currency,promotion.currency AS approved_currency
        FROM retail_promotion_item i JOIN product p ON p.product_id=i.product_id
        JOIN retail_promotion promotion ON promotion.promotion_id=i.promotion_id
        WHERE i.promotion_id IN (
        """
            + parameters
            + ") ORDER BY i.promotion_id,i.product_id",
        row -> {
          long price = row.getLong("promotion_price_minor");
          long version = row.getLong("after_version");
          long currentPrice = row.getLong("current_price");
          long currentVersion = row.getLong("current_version");
          String currentCurrency = row.getString("current_currency");
          targets
              .computeIfAbsent(row.getString("promotion_id"), ignored -> new ArrayList<>())
              .add(
                  new PromotionTarget(
                      row.getString("product_id"),
                      row.getLong("approved_base_price_minor"),
                      price,
                      row.getLong("before_version"),
                      version,
                      row.getString("event_id"),
                      currentPrice,
                      currentCurrency,
                      currentVersion,
                      currentPrice != price
                          || !currentCurrency.equals(row.getString("approved_currency"))));
        },
        rows.stream().map(Promotion::promotionId).toArray());
    return rows.stream()
        .map(
            row ->
                new Promotion(
                    row.promotionId(),
                    row.name(),
                    row.currency(),
                    row.discountBasisPoints(),
                    row.startsAt(),
                    row.endsAt(),
                    row.state(),
                    row.version(),
                    row.createdAt(),
                    row.updatedAt(),
                    row.appliedAt(),
                    row.sourceChangeId(),
                    List.copyOf(targets.getOrDefault(row.promotionId(), List.of()))))
        .toList();
  }

  private Promotion mapPromotion(ResultSet row, int index) throws SQLException {
    Instant ends = instant(row, "ends_at");
    String state = clock.instant().isBefore(ends) ? "active" : "ended";
    return new Promotion(
        row.getString("promotion_id"),
        row.getString("name"),
        row.getString("currency"),
        row.getInt("discount_basis_points"),
        instant(row, "starts_at"),
        ends,
        state,
        row.getLong("version"),
        instant(row, "created_at"),
        instant(row, "updated_at"),
        instant(row, "applied_at"),
        row.getString("source_change_id"),
        List.of());
  }

  private Campaign mapCampaign(ResultSet row, int index) throws SQLException {
    return new Campaign(
        row.getString("campaign_id"),
        row.getString("name"),
        row.getString("objective"),
        row.getString("audience"),
        row.getString("copy_text"),
        row.getString("channel"),
        row.getString("currency"),
        row.getObject("budget_minor", Long.class),
        instant(row, "starts_at"),
        instant(row, "ends_at"),
        row.getString("state"),
        row.getLong("version"),
        instant(row, "created_at"),
        instant(row, "updated_at"),
        row.getString("source_change_id"),
        row.getObject("spend_minor", Long.class),
        row.getObject("revenue_minor", Long.class),
        row.getString("observation_source_kind"),
        row.getString("observation_source_ref"),
        instant(row, "observed_at"),
        instant(row, "observation_start"),
        instant(row, "observation_end"),
        row.getString("fixture_version"));
  }

  private static Instant instant(ResultSet row, String name) throws SQLException {
    Timestamp value = row.getTimestamp(name);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static void bounds(int limit, int offset) {
    if (limit < 1 || limit > 100 || offset < 0 || offset > 10_000) {
      throw MerchantService.invalid("Invalid marketing list bounds");
    }
  }
}
