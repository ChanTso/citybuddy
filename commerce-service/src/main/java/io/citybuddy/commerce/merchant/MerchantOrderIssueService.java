package io.citybuddy.commerce.merchant;

import io.citybuddy.commerce.merchant.MerchantOrderIssueRepository.OrderIssue;
import java.util.List;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class MerchantOrderIssueService {
  private final MerchantOrderIssueRepository repository;

  public MerchantOrderIssueService(MerchantOrderIssueRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<OrderIssue> list(int limit) {
    if (limit < 1 || limit > 100) {
      throw new MerchantException(400, "VALIDATION", "Issue limit must be between 1 and 100");
    }
    return repository.list(limit);
  }
}
