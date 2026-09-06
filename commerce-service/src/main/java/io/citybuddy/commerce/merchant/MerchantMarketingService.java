package io.citybuddy.commerce.merchant;

import io.citybuddy.commerce.merchant.MerchantMarketingModels.Campaign;
import io.citybuddy.commerce.merchant.MerchantMarketingModels.Promotion;
import java.util.List;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class MerchantMarketingService {
  private final MerchantMarketingRepository repository;

  public MerchantMarketingService(MerchantMarketingRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Campaign> campaigns(int limit, int offset) {
    pagination(limit, offset);
    return repository.campaigns(limit, offset);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Campaign campaign(String id) {
    MerchantService.requireText(id, "campaignId", 64);
    return repository
        .campaign(id)
        .orElseThrow(() -> new MerchantException(404, "NOT_FOUND", "Campaign not found"));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Promotion> promotions(int limit, int offset) {
    pagination(limit, offset);
    return repository.promotions(limit, offset);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Promotion promotion(String id) {
    MerchantService.requireText(id, "promotionId", 64);
    return repository
        .promotion(id)
        .orElseThrow(() -> new MerchantException(404, "NOT_FOUND", "Promotion not found"));
  }

  private static void pagination(int limit, int offset) {
    if (limit < 1 || limit > 50 || offset < 0 || offset > 10000) {
      throw MerchantService.invalid("Invalid marketing pagination");
    }
  }
}
